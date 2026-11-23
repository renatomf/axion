package dev.finaxis.core.rails.security;

import dev.finaxis.core.rails.application.CreditsReceived;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The filter chain of {@code /rails/**}, where the Pix rails deliver their messages (billing design,
 * section 7.1). It is neither {@code /v1}, with API keys, nor {@code /internal}, with members'
 * tokens: a request is authenticated by the rails' signature alone, and the public reverse proxy
 * does not route the path.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SigningProperties.class)
class RailsSecurityConfiguration {

    // Ahead of the access module's chains; its last one matches every path.
    @Bean
    @Order(0)
    SecurityFilterChain railsFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver,
            SigningProperties signing,
            CreditsReceived received,
            Clock clock) {
        http.securityMatcher("/rails/**");
        http.authorizeHttpRequests(requests -> requests.anyRequest().authenticated());

        var verifier = new SignatureVerifier(signing.publicKeys(), clock);
        var limits = new SigningKeyRateLimits(clock);
        http.addFilterBefore(
                new SignedMessageFilter(verifier, limits, received, resolver), AnonymousAuthenticationFilter.class);

        // The same headers as every other response of the core, which serves no HTML.
        http.headers(headers -> headers.contentSecurityPolicy(
                        csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.csrf(csrf -> csrf.disable());
        http.formLogin(form -> form.disable());
        http.httpBasic(basic -> basic.disable());
        http.logout(logout -> logout.disable());
        return http.build();
    }
}
