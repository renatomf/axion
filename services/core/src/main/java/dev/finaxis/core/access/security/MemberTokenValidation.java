package dev.finaxis.core.access.security;

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
 * The claims a member's access token must carry for the core to accept it (tenancy and access
 * design, section 4.1). The signature is checked before these rules run, by the decoder.
 *
 * <p>Every rule must hold; the first that fails rejects the token:
 *
 * <ul>
 *   <li>{@code exp} is present and in the future, and {@code nbf}, when present, in the past, with
 *       {@link #CLOCK_SKEW} of tolerance for clocks that drift apart;
 *   <li>{@code iss} is the realm;
 *   <li>{@code aud} contains {@value #AUDIENCE}: Keycloak adds it only to access tokens of the
 *       clients configured for the core, so ID and refresh tokens, whose audience is the client or
 *       the realm, are rejected;
 *   <li>{@code azp}, the client the member logged in through, is one of the allowed clients;
 *   <li>{@code sub}, the Keycloak user, is present and not blank: the core finds the member's
 *       organizations and roles by it.
 * </ul>
 */
final class MemberTokenValidation {

    /** The audience Keycloak puts in access tokens meant for the core. */
    static final String AUDIENCE = "finaxis-core";

    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** The "authorized party" claim of OpenID Connect: the client the token was issued to. */
    static final String AUTHORIZED_PARTY = "azp";

    private MemberTokenValidation() {}

    /**
     * @param issuer the realm's issuer URL, exactly as Keycloak writes it in {@code iss}
     * @param clients the client IDs whose tokens the core accepts
     * @param clock the clock {@code exp} and {@code nbf} are compared with
     */
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
