package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Adds the permission check and the {@code Caller} parameter to Spring MVC.
 *
 * <p>Spring Boot configures Spring MVC itself and then calls every {@link WebMvcConfigurer} bean, so
 * this class adds to Boot's setup instead of replacing it. The interceptor covers the two APIs only;
 * Spring Boot's own {@code /error} page and the actuator stay outside it.
 */
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
        registry.addInterceptor(new PermissionInterceptor(tenancy, clock)).addPathPatterns("/internal/**", "/v1/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CallerArgumentResolver());
    }
}
