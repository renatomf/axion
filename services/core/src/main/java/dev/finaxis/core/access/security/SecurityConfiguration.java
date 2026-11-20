package dev.finaxis.core.access.security;

import dev.finaxis.core.access.AccessApi;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
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
 * The HTTP security of the core: which requests are allowed, before any controller runs.
 *
 * <p>Spring Security is a chain of servlet filters in front of Spring MVC. Spring Boot builds the
 * {@link HttpSecurity} passed to each method below; the {@link SecurityFilterChain} beans returned
 * here replace Boot's default chain. A request goes through the first chain, in {@code @Order},
 * whose {@code securityMatcher} matches it, and through no other:
 *
 * <ol>
 *   <li>{@link #internalFilterChain}: {@code /internal/**}, the API of the console's BFF, open to
 *       members with a valid access token from Keycloak;
 *   <li>{@link #publicApiFilterChain}: {@code /v1/**}, the public API, open to tenants' systems
 *       with a working API key;
 *   <li>{@link #securityFilterChain}: every other request.
 * </ol>
 *
 * <p>Access is denied by default: a request is allowed only if a rule below opens it. A new endpoint
 * therefore stays closed until it gets an explicit rule, instead of being open because someone forgot
 * one. Outside the two APIs, only the health endpoint is public, for load balancers and
 * container orchestrators.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

    static final String CONTENT_SECURITY_POLICY = "default-src 'none'; frame-ancestors 'none'";

    /**
     * Members on {@code /internal}: the core acts as an OAuth 2.0 resource server. The bearer token
     * filter reads the token from the {@code Authorization} header only (a token in the query string
     * or the body is ignored, as Spring does by default), decodes it with {@code jwtDecoder} and, if
     * it is valid, authenticates the request with the token's claims. Only access tokens are accepted
     * here: an API key is not a JWT and fails to decode, so it gets a {@code 401}.
     *
     * @param jwtDecoder the decoder of {@link MemberTokenConfiguration}
     */
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
                // A missing, malformed or invalid token gets the same 401 as any other rejection.
                .authenticationEntryPoint(rejections)
                .accessDeniedHandler(rejections));

        return build(http, rejections);
    }

    /**
     * Tenants' systems on {@code /v1}: {@link ApiKeyAuthenticationFilter} authenticates the API key
     * in the {@code Authorization} header, and every request must be authenticated. Only API keys
     * are accepted here: an access token is not a key and gets a {@code 401}. Which scopes an
     * endpoint needs is checked after the chain, against its {@code @RequiresPermission}.
     *
     * <p>{@code addFilterBefore} places the filter before Spring Security's anonymous filter, which
     * marks a request that nothing authenticated as anonymous, so the rule below sees the key.
     *
     * @param access authenticates the key
     * @param meters where the authentication failures are counted
     */
    @Bean
    @Order(2)
    SecurityFilterChain publicApiFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            AccessApi access,
            MeterRegistry meters) {
        http.securityMatcher("/v1/**");
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        ProblemDetailsSecurityHandler rejections = new ProblemDetailsSecurityHandler(resolver);
        var failures = new AuthenticationFailures(meters);
        http.addFilterBefore(
                new ApiKeyAuthenticationFilter(access, rejections, failures), AnonymousAuthenticationFilter.class);

        return build(http, rejections);
    }

    /**
     * Every request neither API matches. This chain authenticates nothing, so anything but the
     * health endpoint gets a {@code 401}.
     *
     * @param resolver Spring MVC's exception resolver, which applies the {@code web} module's error
     *     format; the qualifier picks it out of the several resolvers in the context
     */
    @Bean
    @Order(3)
    SecurityFilterChain securityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
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

        return build(http, new ProblemDetailsSecurityHandler(resolver));
    }

    /** The settings both chains share. */
    private static SecurityFilterChain build(HttpSecurity http, ProblemDetailsSecurityHandler rejections) {
        // Rejected requests get the same Problem Details as every other error.
        http.exceptionHandling(
                exceptions -> exceptions.authenticationEntryPoint(rejections).accessDeniedHandler(rejections));

        // Security headers for a JSON API, on every response, errors included. Spring Security
        // already sends X-Content-Type-Options: nosniff, X-Frame-Options: DENY, Cache-Control:
        // no-store and, on HTTPS requests, Strict-Transport-Security. The core serves no HTML, so
        // the policy below lets a browser load nothing from a response and frame it nowhere, and no
        // URL of the core leaks to other sites through the Referer header.
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));

        // Clients authenticate on every request, with an access token or an API key, so the core
        // never creates an HTTP session.
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
