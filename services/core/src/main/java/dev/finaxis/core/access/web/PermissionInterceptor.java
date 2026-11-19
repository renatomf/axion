package dev.finaxis.core.access.web;

import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.NoPermissionRequired;
import dev.finaxis.core.access.RequiresPermission;
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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Establishes the {@link MemberCaller} of a tenant-scoped request and checks its permission, after
 * Spring MVC has chosen the controller method and before it runs (tenancy design, sections 5.1 and
 * 5.2).
 *
 * <p>A {@link HandlerInterceptor} is Spring MVC's hook around a controller method. Unlike a servlet
 * filter, it knows which method will handle the request, so it can read that method's annotations
 * and the path variables of its mapping. An exception thrown here goes to the same error handler as
 * one thrown by the controller.
 *
 * <p>For a method with {@link RequiresPermission}, in this order:
 *
 * <ol>
 *   <li>the {@code {tenant_id}} of the path is parsed; a malformed ID answers {@code 404};
 *   <li>the member's membership is looked up through that tenant, using only the token's {@code
 *       sub}; a tenant that does not exist or that the member does not belong to answers {@code
 *       404}, so nobody learns which tenants exist;
 *   <li>a tenant that is not active answers {@code 403 tenant_inactive};
 *   <li>roles become permissions; without the required one, {@code 403 insufficient_permission}.
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

    private static final Logger LOG = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final TenancyApi tenancy;

    PermissionInterceptor(TenancyApi tenancy) {
        this.tenancy = tenancy;
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
        request.setAttribute(CALLER_ATTRIBUTE, member(request, required.value()));
        return true;
    }

    private MemberCaller member(HttpServletRequest request, Permission required) {
        TenantId tenant = tenantInPath(request);
        Membership membership = tenancy.membership(subject(), tenant).orElseThrow(AccessProblems::notFound);
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
        return new MemberCaller(tenant, membership.member(), permissions);
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

    /**
     * The member's Keycloak {@code sub}. Only {@code /internal} reaches a tenant-scoped endpoint
     * for now, and its filter chain authenticates every request with a validated access token, which
     * always has a subject; API keys on {@code /v1} arrive in PR 1.21.
     */
    private static String subject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            throw new IllegalStateException("A tenant-scoped request must be authenticated with an access token");
        }
        return Objects.requireNonNull(token.getToken().getSubject());
    }
}
