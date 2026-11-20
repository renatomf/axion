package dev.finaxis.core.access.security;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/** Decodes members' access tokens, JWTs signed by the Keycloak realm (tenancy design, section 4.1). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MemberTokenProperties.class)
class MemberTokenConfiguration {

    @Bean
    JwtDecoder memberTokenDecoder(MemberTokenProperties properties, Clock clock) {
        // Built on the first token, not at startup, so the core starts without Keycloak.
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(properties.issuer())
                    // Pinned, so a token cannot choose none or HS256.
                    .jwsAlgorithm(SignatureAlgorithm.RS256)
                    .build();
            decoder.setJwtValidator(MemberTokenValidation.validator(properties.issuer(), properties.clients(), clock));
            return decoder;
        });
    }
}
