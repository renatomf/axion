package dev.axion.core.access.web;

import dev.axion.core.access.ApiKeyCaller;
import dev.axion.core.access.Caller;
import dev.axion.core.access.MemberCaller;
import dev.axion.core.access.NoPermissionRequired;
import dev.axion.core.access.RequiresPermission;
import dev.axion.core.access.RequiresStepUp;
import dev.axion.core.access.security.ApiKeyAuthentication;
import dev.axion.core.shared.PublicId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Membership;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.Role;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.tenancy.TenantStatus;
import dev.axion.core.tenancy.TenantView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Establishes the {@link Caller} of a request and checks the permission its endpoint declares
 * (tenancy design, sections 5.1 and 5.2).
 *
 * <p>An API key brings its own caller and never passes {@link RequiresStepUp}. A member's token
 * names no tenant, so the tenant in the path is checked against the member's memberships, in this
 * order: unknown or foreign tenant, {@code 404}; inactive tenant, {@code 403 tenant_inactive};
 * missing permission, {@code 403 insufficient_permission}; old login, {@code 403
 * step_up_required}. An endpoint that declares no permission is denied.
 *
 * <p>It runs before the body is read, so a caller who may not act learns nothing about a valid body.
 */
final class PermissionInterceptor implements HandlerInterceptor {

    static final String CALLER_ATTRIBUTE = PermissionInterceptor.class.getName() + ".caller";

    static final String TENANT_ID = "tenant_id";

    static final String AUTH_TIME = "auth_time";

    private static final Logger LOG = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final TenancyApi tenancy;
    private final Clock clock;

    PermissionInterceptor(TenancyApi tenancy, Clock clock) {
        this.tenancy = tenancy;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            // Not a controller method, such as an unknown path.
            return true;
        }
        if (method.hasMethodAnnotation(NoPermissionRequired.class)) {
            return true;
        }
        RequiresPermission required = method.getMethodAnnotation(RequiresPermission.class);
        if (required == null) {
            LOG.error("Endpoint {} declares no permission and is denied", method.getShortLogMessage());
            throw AccessProblems.insufficientPermission();
        }
        boolean stepUp = method.hasMethodAnnotation(RequiresStepUp.class);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Caller caller = switch (authentication) {
            case ApiKeyAuthentication key -> apiKey(key.getPrincipal(), required.value(), stepUp);
            case JwtAuthenticationToken token -> member(request, token.getToken(), required.value(), stepUp);
            case null, default ->
                throw new IllegalStateException(
                        "A tenant-scoped request must be authenticated with an API key or an access token");
        };
        request.setAttribute(CALLER_ATTRIBUTE, caller);
        return true;
    }

    private static ApiKeyCaller apiKey(ApiKeyCaller caller, Permission required, boolean stepUp) {
        if (!caller.permissions().contains(required) || stepUp) {
            throw AccessProblems.insufficientPermission();
        }
        return caller;
    }

    private MemberCaller member(HttpServletRequest request, Jwt token, Permission required, boolean stepUp) {
        TenantId tenant = tenantInPath(request);
        // MemberTokenValidation rejects a token without a subject.
        String subject = Objects.requireNonNull(token.getSubject());
        Membership membership = tenancy.membership(subject, tenant).orElseThrow(AccessProblems::notFound);
        TenantView view = membership.tenants().stream()
                .filter(candidate -> candidate.id().equals(tenant))
                .findFirst()
                .orElseThrow(AccessProblems::notFound);
        if (view.status() != TenantStatus.ACTIVE) {
            throw AccessProblems.tenantInactive();
        }
        Set<Permission> permissions = Role.permissionsOf(membership.roles());
        if (!permissions.contains(required)) {
            throw AccessProblems.insufficientPermission();
        }
        if (stepUp && !RecentAuthentication.isRecent(token.getClaimAsInstant(AUTH_TIME), clock)) {
            throw AccessProblems.stepUpRequired();
        }
        return new MemberCaller(tenant, view.environment(), membership.member(), permissions);
    }

    private static TenantId tenantInPath(HttpServletRequest request) {
        @SuppressWarnings("unchecked")
        Map<String, String> variables =
                (@Nullable Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        @Nullable String text = variables == null ? null : variables.get(TENANT_ID);
        if (text == null) {
            throw new IllegalStateException("A tenant-scoped endpoint must have {" + TENANT_ID + "} in its path");
        }
        try {
            return new TenantId(PublicId.parse(TenantId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw AccessProblems.notFound();
        }
    }
}
