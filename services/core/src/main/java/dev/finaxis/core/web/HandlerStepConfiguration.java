package dev.finaxis.core.web;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@Configuration(proxyBeanMethods = false)
class HandlerStepConfiguration {

    @Bean
    static WebMvcRegistrations handlerSteps(ObjectProvider<HandlerStep> steps) {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new HandlerStepAdapter(steps);
            }
        };
    }
}
