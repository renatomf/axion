package dev.axion.core.access.web;

import dev.axion.core.access.Caller;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Passes the {@link Caller} that {@link PermissionInterceptor} established; no client can supply it. */
final class CallerArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return Caller.class.isAssignableFrom(parameter.getParameterType());
    }

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
