package dev.finaxis.core.access.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.SignedJWT;
import dev.finaxis.core.KeycloakRealm;
import dev.finaxis.core.KeycloakTestConfiguration;
import dev.finaxis.core.PostgresTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

/**
 * Members' access tokens on {@code /internal}, against a real Keycloak running the {@code finaxis}
 * realm (tenancy and access design, sections 4.1 and 8).
 *
 * <p>The requests go to {@code /internal/me}: an accepted token reaches the endpoint and gets a
 * {@code 200}; a rejected one never gets that far and is a {@code 401}. What the endpoint answers is
 * tested in {@code MeEndpointTest}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class})
@AutoConfigureMockMvc
class MemberAuthenticationTest {

    private static final String INTERNAL_PATH = "/internal/me";

    private final MockMvcTester mvc;
    private final KeycloakRealm realm;

    @Autowired
    MemberAuthenticationTest(MockMvcTester mvc, KeycloakRealm realm) {
        this.mvc = mvc;
        this.realm = realm;
    }

    private MvcTestResultAssert internalWith(String token) {
        return assertThat(mvc.get().uri(INTERNAL_PATH).header("Authorization", "Bearer " + token));
    }

    private static void isUnauthorized(MvcTestResultAssert response) {
        response.hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().isLenientlyEqualTo("""
                {"status": 401, "detail": "Authentication is required.", "code": "unauthorized"}
                """);
    }

    @Test
    void acceptsAnAccessTokenOfTheRealm() {
        internalWith(realm.login(KeycloakRealm.TEST_CLIENT).accessToken()).hasStatus(HttpStatus.OK);
    }

    @Test
    void rejectsARequestWithoutAToken() {
        isUnauthorized(assertThat(mvc.get().uri(INTERNAL_PATH)));
    }

    /** Same realm, same audience, same signature: only the client differs. */
    @Test
    void rejectsAnAccessTokenIssuedToAClientTheCoreDoesNotAccept() {
        isUnauthorized(internalWith(realm.login(KeycloakRealm.OTHER_CLIENT).accessToken()));
    }

    /** An ID token proves a login to the client; it is not a credential for the core. */
    @Test
    void rejectsAnIdToken() {
        isUnauthorized(internalWith(realm.login(KeycloakRealm.TEST_CLIENT).idToken()));
    }

    @Test
    void rejectsATokenWithAnAlteredSignature() {
        String token = realm.login(KeycloakRealm.TEST_CLIENT).accessToken();
        int middle = token.lastIndexOf('.') + (token.length() - token.lastIndexOf('.')) / 2;
        char altered = token.charAt(middle) == 'A' ? 'B' : 'A';
        isUnauthorized(internalWith(token.substring(0, middle) + altered + token.substring(middle + 1)));
    }

    /** The realm's claims with {@code alg: none}: a token that claims it needs no signature. */
    @Test
    void rejectsAnUnsignedToken() throws ParseException {
        String payload = SignedJWT.parse(realm.login(KeycloakRealm.TEST_CLIENT).accessToken())
                .getParsedParts()[1]
                .toString();
        String header = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        isUnauthorized(internalWith(header + "." + payload + "."));
    }

    /** The realm's claims signed with HMAC: whoever holds the secret could mint tokens. */
    @Test
    void rejectsATokenSignedWithASymmetricAlgorithm() throws ParseException, JOSEException {
        SignedJWT realmToken =
                SignedJWT.parse(realm.login(KeycloakRealm.TEST_CLIENT).accessToken());
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        SignedJWT forged = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), realmToken.getJWTClaimsSet());
        forged.sign(new MACSigner(secret));
        isUnauthorized(internalWith(forged.serialize()));
    }

    /** An API key on /internal is a credential of the wrong kind. */
    @Test
    void rejectsAnApiKey() {
        isUnauthorized(internalWith("sk_test_not-a-real-key"));
    }

    /** /v1 accepts API keys only: a valid member token there is a credential of the wrong kind. */
    @Test
    void rejectsAnAccessTokenOnThePublicApi() {
        isUnauthorized(assertThat(mvc.get()
                .uri("/v1/charges")
                .header(
                        "Authorization",
                        "Bearer " + realm.login(KeycloakRealm.TEST_CLIENT).accessToken())));
    }
}
