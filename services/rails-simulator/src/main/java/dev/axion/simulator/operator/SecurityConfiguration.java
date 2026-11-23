package dev.axion.simulator.operator;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The simulator's HTTP security: only the health endpoint is open; every other request must carry the
 * operator token.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorProperties.class)
class SecurityConfiguration {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            OperatorProperties operator) {
        http.authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                .permitAll()
                // An error forwarded to /error keeps its status instead of becoming a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .authenticated());

        var rejections = new ProblemDetailsSecurityHandler(resolver);
        http.addFilterBefore(new OperatorTokenFilter(operator, rejections), AnonymousAuthenticationFilter.class);
        http.exceptionHandling(
                exceptions -> exceptions.authenticationEntryPoint(rejections).accessDeniedHandler(rejections));

        // A JSON API serves no HTML: a browser may load nothing from a response, nor frame it.
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // No cookies reach the simulator, so there is no session for a forged request to ride on.
        http.csrf(csrf -> csrf.disable());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        http.logout(logout -> logout.disable());

        return http.build();
    }
}
