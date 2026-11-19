package dev.finaxis.core.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The claim rules of a member's access token, without Keycloak: each test starts from a token that
 * passes and changes one claim. The signature is not involved; it is checked against a real
 * Keycloak in {@code MemberAuthenticationTest}.
 */
class MemberTokenValidationTest {

    private static final String ISSUER = "http://keycloak.test/realms/finaxis";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final OAuth2TokenValidator<Jwt> validator =
            MemberTokenValidation.validator(ISSUER, Set.of("finaxis-console"), Clock.fixed(NOW, ZoneOffset.UTC));

    /** A token as Keycloak issues it to the console, five minutes long, issued a minute ago. */
    private static Jwt.Builder validToken() {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("8b4a1c9e-0000-4000-8000-000000000001")
                .issuer(ISSUER)
                .audience(List.of("finaxis-core"))
                .claim("azp", "finaxis-console")
                .issuedAt(NOW.minusSeconds(60))
                .expiresAt(NOW.plusSeconds(240));
    }

    private boolean accepts(UnaryOperator<Jwt.Builder> change) {
        return !validator.validate(change.apply(validToken()).build()).hasErrors();
    }

    @Test
    void acceptsATokenOfTheRealmForTheCore() {
        assertThat(accepts(token -> token)).isTrue();
    }

    @Test
    void acceptsATokenWithOtherAudiencesBesidesTheCore() {
        assertThat(accepts(token -> token.audience(List.of("account", "finaxis-core"))))
                .isTrue();
    }

    @Test
    void rejectsATokenOfAnotherIssuer() {
        assertThat(accepts(token -> token.issuer("http://keycloak.test/realms/other")))
                .isFalse();
    }

    /** An ID token's audience is the client; a refresh token's is the realm. */
    @Test
    void rejectsATokenNotMeantForTheCore() {
        assertThat(accepts(token -> token.audience(List.of("finaxis-console")))).isFalse();
        assertThat(accepts(token -> token.audience(List.of(ISSUER)))).isFalse();
    }

    @Test
    void rejectsATokenIssuedToAnotherClient() {
        assertThat(accepts(token -> token.claim("azp", "finaxis-other"))).isFalse();
    }

    @Test
    void rejectsATokenWithoutAnAuthorizedParty() {
        assertThat(accepts(token -> token.claims(claims -> claims.remove("azp"))))
                .isFalse();
    }

    @Test
    void rejectsATokenThatNeverExpires() {
        assertThat(accepts(token -> token.claims(claims -> claims.remove("exp"))))
                .isFalse();
    }

    /** Clocks drift: a token expired up to 30 seconds ago is still accepted, not a second more. */
    @Test
    void toleratesThirtySecondsOfClockSkewOnExpiry() {
        assertThat(accepts(token -> token.expiresAt(NOW.minusSeconds(30)))).isTrue();
        assertThat(accepts(token -> token.expiresAt(NOW.minusSeconds(31)))).isFalse();
    }

    @Test
    void toleratesThirtySecondsOfClockSkewOnNotBefore() {
        assertThat(accepts(token -> token.notBefore(NOW.plusSeconds(30)))).isTrue();
        assertThat(accepts(token -> token.notBefore(NOW.plusSeconds(31)))).isFalse();
    }
}
