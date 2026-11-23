package dev.axion.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * A step around every controller method. Steps run after the handler interceptors and before the
 * method's arguments are resolved, so a step sees the request before its body is read and the
 * response before it is written. A module contributes a step as a bean; steps run by {@link
 * #getOrder()}, lowest first, each inside the one before.
 */
public interface HandlerStep extends Ordered {

    /** The order of the step that binds the caller's tenant: steps after it run with the tenant bound. */
    int TENANT_BINDING = 100;

    /** The order of the step that runs a public API write once per idempotency key, inside the tenant binding. */
    int IDEMPOTENCY = 200;

    /**
     * Runs the step, which calls {@code next} to go on, possibly with a wrapped request or response,
     * or answers the request itself.
     */
    @Nullable
    ModelAndView invoke(HttpServletRequest request, HttpServletResponse response, HandlerMethod handler, Next next)
            throws Exception;

    /** The rest of the steps, then the controller method. */
    @FunctionalInterface
    interface Next {
        @Nullable
        ModelAndView invoke(HttpServletRequest request, HttpServletResponse response) throws Exception;
    }
}
