package dev.finaxis.core.access;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The HTTP security of the core: which requests are allowed, before any controller runs.
 *
 * <p>Spring Security is a chain of servlet filters in front of Spring MVC. Spring Boot builds the
 * {@link HttpSecurity} passed to {@link #securityFilterChain}; the {@link SecurityFilterChain} bean
 * returned here replaces Boot's default chain.
 *
 * <p>Access is denied by default: a request is allowed only if a rule below opens it. A new endpoint
 * therefore stays closed until it gets an explicit rule, instead of being open because someone forgot
 * one. Today only the health endpoint is public, for load balancers and container orchestrators.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) {
        http.authorizeHttpRequests(requests -> requests
                // /actuator/health and its probes, wherever the management base path is.
                .requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                .permitAll()
                // An error forwarded to Spring Boot's /error page keeps its original status
                // instead of becoming a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .denyAll());

        // Clients authenticate on every request (API keys and service tokens arrive in phase 1),
        // so the core never creates an HTTP session.
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // CSRF abuses credentials a browser sends on its own, such as cookies. The core uses no
        // cookies; the BFF, which does, protects itself against CSRF.
        http.csrf(csrf -> csrf.disable());
        // Browser-oriented features that a JSON API must not offer: a login form, a Basic
        // authentication prompt and a /logout endpoint that redirects.
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        http.logout(logout -> logout.disable());

        return http.build();
    }
}
