package dev.finaxis.core.access.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Decodes and validates members' access tokens: JWTs signed by the Keycloak realm.
 *
 * <p>The decoder reads the realm's OpenID configuration from {@code
 * <issuer>/.well-known/openid-configuration} and, from the URL it names, the realm's public keys
 * (the JWK set). It does so on the first token, not at startup, so the core starts while Keycloak is
 * still starting, and tests that send no token never need a Keycloak. The keys are then cached.
 *
 * <p>A token is accepted only if its signature verifies with one of those keys using {@code RS256},
 * the realm's algorithm. A token whose header names another algorithm, such as {@code none} or the
 * symmetric {@code HS256}, is rejected before any key is tried, so an attacker cannot choose how the
 * signature is checked. The claims are then checked by {@link MemberTokenValidation}.
 *
 * <p>Spring Boot would build a decoder on its own from {@code
 * spring.security.oauth2.resourceserver.jwt.*} properties; it backs off when this bean exists. The
 * bean is explicit so every rule the core applies is visible in one place.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemberTokenProperties.class)
class MemberTokenConfiguration {

    @Bean
    JwtDecoder memberTokenDecoder(MemberTokenProperties properties, Clock clock) {
        // Building a NimbusJwtDecoder from the issuer fetches the OpenID configuration right away.
        // SupplierJwtDecoder defers that to the first token and keeps the decoder once it is built;
        // if Keycloak cannot be reached, the next token tries again.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuer())
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(MemberTokenValidation.validator(properties.issuer(), properties.clients(), clock));
            return decoder;
        });
    }
}
