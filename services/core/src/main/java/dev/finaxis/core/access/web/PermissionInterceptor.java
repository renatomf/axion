package dev.finaxis.core.access.web;

import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.Caller;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.RequiresStepUp;
import dev.finaxis.core.access.security.ApiKeyAuthentication;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Membership;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.Role;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantStatus;
import dev.finaxis.core.tenancy.TenantView;
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
 * Establishes the {@link Caller} of a tenant-scoped request and checks its permission, after Spring
 * MVC has chosen the controller method and before it runs (tenancy design, sections 5.1 and 5.2).
 *
 * <p>A {@link HandlerInterceptor} is Spring MVC's hook around a controller method. Unlike a servlet
 * filter, it knows which method will handle the request, so it can read that method's annotations
 * and the path variables of its mapping. An exception thrown here goes to the same error handler as
 * one thrown by the controller.
 *
 * <p>The caller depends on how the security filter chain authenticated the request. An API key on
 * {@code /v1} already carries its {@link ApiKeyCaller}: its tenant, which the filter found active,
 * and its scopes. A key without the required scope gets {@code 403 insufficient_permission}, and so
 * does a key on a method with {@link RequiresStepUp}, since a key cannot authenticate again.
 *
 * <p>A member's access token on {@code /internal} names no tenant, so for a method with {@link
 * RequiresPermission}, in this order:
 *
 * <ol>
 *   <li>the {@code {tenant_id}} of the path is parsed; a malformed ID answers {@code 404};
 *   <li>the member's membership is looked up through that tenant, using only the token's {@code
 *       sub}; a tenant that does not exist or that the member does not belong to answers {@code
 *       404}, so nobody learns which tenants exist;
 *   <li>a tenant that is not active answers {@code 403 tenant_inactive};
 *   <li>roles become permissions; without the required one, {@code 403 insufficient_permission};
 *   <li>if the method also carries {@link RequiresStepUp}, a member whose {@code auth_time} is more
 *       than five minutes old, or missing, gets {@code 403 step_up_required}. This comes last:
 *       authenticating again would not help a member who lacks the permission.
 * </ol>
 *
 * <p>The caller is then kept as a request attribute, where {@link CallerArgumentResolver} finds it.
 * A method with neither annotation is denied: the architecture test should have stopped it, and
 * denying keeps an endpoint closed even if the test were skipped.
 */
final class PermissionInterceptor implements HandlerInterceptor {

    /** The request attribute holding the established caller. */
    static final String CALLER_ATTRIBUTE = PermissionInterceptor.class.getName() + ".caller";

    /** The path variable that names the tenant, as in {@code /internal/tenants/{tenant_id}/…}. */
    static final String TENANT_ID = "tenant_id";

    /** The token claim with the moment the member last authenticated, in seconds. */
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
            // Not a controller method, such as the handler of an unknown path, which answers 404.
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

    /** The key's own caller, if its scopes include the required permission. */
    private static ApiKeyCaller apiKey(ApiKeyCaller caller, Permission required, boolean stepUp) {
        if (!caller.permissions().contains(required) || stepUp) {
            throw AccessProblems.insufficientPermission();
        }
        return caller;
    }

    private MemberCaller member(HttpServletRequest request, Jwt token, Permission required, boolean stepUp) {
        TenantId tenant = tenantInPath(request);
        // MemberTokenValidation rejects a token without a subject, so it is never null here.
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

    /**
     * The tenant the path names. Spring MVC stores the path variables of the chosen mapping as a
     * request attribute before interceptors run.
     */
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
