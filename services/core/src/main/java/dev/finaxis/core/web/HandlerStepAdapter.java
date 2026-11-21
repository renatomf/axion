package dev.finaxis.core.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Runs every controller method through the {@link HandlerStep}s, in their order. */
final class HandlerStepAdapter extends RequestMappingHandlerAdapter {

    private final ObjectProvider<HandlerStep> stepBeans;
    private List<HandlerStep> steps = List.of();

    HandlerStepAdapter(ObjectProvider<HandlerStep> stepBeans) {
        this.stepBeans = stepBeans;
    }

    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        steps = stepBeans.orderedStream().toList();
    }

    @Override
    protected @Nullable ModelAndView invokeHandlerMethod(
            HttpServletRequest request, HttpServletResponse response, HandlerMethod handlerMethod) throws Exception {
        return invoke(0, request, response, handlerMethod);
    }

    private @Nullable ModelAndView invoke(
            int index, HttpServletRequest request, HttpServletResponse response, HandlerMethod handlerMethod)
            throws Exception {
        if (index == steps.size()) {
            return super.invokeHandlerMethod(request, response, handlerMethod);
        }
        return steps.get(index)
                .invoke(
                        request,
                        response,
                        handlerMethod,
                        (nextRequest, nextResponse) -> invoke(index + 1, nextRequest, nextResponse, handlerMethod));
    }
}
