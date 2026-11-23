package dev.axion.core.access.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/**
 * The claims a member's access token must carry (tenancy design, section 4.1): {@code exp}, the
 * realm's {@code iss}, the core's {@code aud}, an allowed {@code azp} and a {@code sub}. The
 * audience is what keeps ID and refresh tokens out.
 */
final class MemberTokenValidation {

    static final String AUDIENCE = "finaxis-core";

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    static final String AUTHORIZED_PARTY = "azp";

    private MemberTokenValidation() {}

    static OAuth2TokenValidator<Jwt> validator(String issuer, Set<String> clients, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        // Spring accepts a token without exp by default; a token that never expires is refused.
        timestamps.setAllowEmptyExpiryClaim(false);

        Set<String> allowedClients = Set.copyOf(clients);
        return new DelegatingOAuth2TokenValidator<>(
                timestamps,
                new JwtIssuerValidator(issuer),
                new JwtAudienceValidator(AUDIENCE),
                new JwtClaimValidator<String>(
                        AUTHORIZED_PARTY, client -> client != null && allowedClients.contains(client)),
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()));
    }
}
