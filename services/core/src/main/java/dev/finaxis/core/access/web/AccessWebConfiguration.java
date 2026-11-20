package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Clock;
import java.util.List;
import org.springframework.boot.webmvc.autoconfigure.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

@Configuration(proxyBeanMethods = false)
class AccessWebConfiguration implements WebMvcConfigurer {

    private final TenancyApi tenancy;
    private final Clock clock;

    AccessWebConfiguration(TenancyApi tenancy, Clock clock) {
        this.tenancy = tenancy;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The two APIs only: the error page and the actuator stay outside.
        registry.addInterceptor(new PermissionInterceptor(tenancy, clock)).addPathPatterns("/internal/**", "/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }

    @Bean
    static WebMvcRegistrations tenantBinding() {
        return new WebMvcRegistrations() {
            @Override
            public RequestMappingHandlerAdapter getRequestMappingHandlerAdapter() {
                return new TenantBindingHandlerAdapter();
            }
        };
    }
}
