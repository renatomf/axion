package dev.finaxis.core.access.web;

import dev.finaxis.core.access.Caller;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Passes the {@link Caller} that {@link PermissionInterceptor} established to a controller method
 * that declares a parameter of type {@code Caller}, {@code MemberCaller} or {@code ApiKeyCaller}.
 *
 * <p>A {@link HandlerMethodArgumentResolver} is how Spring MVC fills controller parameters, the same
 * way it fills {@code @PathVariable} or {@code @RequestBody} ones. The caller never comes from the
 * request itself, so no client can supply it.
 */
final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.isAssignableFrom(parameter.getParameterType());
    }

    /**
     * @throws IllegalStateException if there is no caller of the parameter's type: the method lacks
     *     {@code @RequiresPermission}, or asks for a kind of caller its endpoint does not have
     */
    @Override
    public Object resolveArgument(
            MethodParameter parameter,
            @Nullable ModelAndViewContainer container,
            NativeWebRequest request,
            @Nullable WebDataBinderFactory binderFactory) {
        Object caller = request.getAttribute(PermissionInterceptor.CALLER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!parameter.getParameterType().isInstance(caller)) {
            throw new IllegalStateException(
                    "No " + parameter.getParameterType().getSimpleName() + " for " + parameter.getExecutable());
        }
        return caller;
    }
}
