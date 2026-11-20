package dev.finaxis.core.access.web;

import dev.finaxis.core.access.Caller;
import dev.finaxis.core.shared.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Runs a controller method with its caller's tenant in {@link TenantContext} (tenancy design,
 * section 5.3). It runs after {@link PermissionInterceptor}, so the tenant is one already checked;
 * an endpoint without a caller runs with no tenant.
 */
final class TenantBindingHandlerAdapter extends RequestMappingHandlerAdapter {

    @Override
    protected @Nullable ModelAndView invokeHandlerMethod(
            HttpServletRequest request, HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {
        if (request.getAttribute(PermissionInterceptor.CALLER_ATTRIBUTE) instanceof Caller caller) {
            return TenantContext.call(
                    caller.tenant(), () -> super.invokeHandlerMethod(request, response, handlerMethod));
        }
        return super.invokeHandlerMethod(request, response, handlerMethod);
    }
}
