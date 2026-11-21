package dev.finaxis.core.access.security;

import dev.finaxis.core.access.AccessApi;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The core's HTTP security, denied by default. Three chains, tried in order: {@code /internal/**}
 * for members' access tokens, {@code /v1/**} for API keys, and everything else, where only the
 * health endpoint is open.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    @Bean
    @Order(1)
    SecurityFilterChain internalFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            JwtDecoder jwtDecoder) {
        http.securityMatcher("/internal/**");
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        ProblemDetailsSecurityHandler rejections = new ProblemDetailsSecurityHandler(resolver);
        http.oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(jwtDecoder))
                .authenticationEntryPoint(rejections)
                .accessDeniedHandler(rejections));

        return build(http, rejections);
    }

    @Bean
    @Order(2)
    SecurityFilterChain publicApiFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            AccessApi access,
            MeterRegistry meters,
            Clock clock) {
        http.securityMatcher("/v1/**");
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        ProblemDetailsSecurityHandler rejections = new ProblemDetailsSecurityHandler(resolver);
        var failures = new AuthenticationFailures(meters);
        http.addFilterBefore(
                new ApiKeyAuthenticationFilter(access, rejections, failures, new RateLimits(clock)),
                AnonymousAuthenticationFilter.class);

        return build(http, rejections);
    }

    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        http.authorizeHttpRequests(requests -> requests.requestMatchers(EndpointRequest.to(HealthEndpoint.class))
                .permitAll()
                // An error forwarded to /error keeps its status instead of becoming a 401.
                .dispatcherTypeMatchers(DispatcherType.ERROR)
                .permitAll()
                .anyRequest()
                .denyAll());

        return build(http, new ProblemDetailsSecurityHandler(resolver));
    }

    private static SecurityFilterChain build(HttpSecurity http, ProblemDetailsSecurityHandler rejections) {
        http.exceptionHandling(
                exceptions -> exceptions.authenticationEntryPoint(rejections).accessDeniedHandler(rejections));

        // A JSON API serves no HTML: a browser may load nothing from a response, nor frame it.
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));

        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        // No cookies reach the core; the BFF, which uses them, protects itself against CSRF.
        http.csrf(csrf -> csrf.disable());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        http.logout(logout -> logout.disable());

        return http.build();
    }
}
