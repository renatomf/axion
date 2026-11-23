package dev.axion.core.access.web;

import dev.axion.core.access.Caller;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.web.HandlerStep;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Runs a controller method with its caller's tenant and environment in {@link TenantContext} (tenancy design,
 * section 5.3). It runs after {@link PermissionInterceptor}, so the tenant is one already checked;
 * an endpoint without a caller runs with no tenant.
 */
final class TenantBindingStep implements HandlerStep {

    @Override
    public int getOrder() {
        return TENANT_BINDING;
    }

    @Override
    public @Nullable ModelAndView invoke(
            HttpServletRequest request, HttpServletResponse response, HandlerMethod handler, Next next)
            throws Exception {
        if (request.getAttribute(PermissionInterceptor.CALLER_ATTRIBUTE) instanceof Caller caller) {
            return TenantContext.call(caller.tenant(), caller.environment(), () -> next.invoke(request, response));
        }
        return next.invoke(request, response);
    }
}
