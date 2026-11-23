package dev.finaxis.core.rails.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.MutableClock;
import dev.finaxis.core.rails.RailsSigner;
import dev.finaxis.core.rails.security.SignatureVerifier.Refusal;
import dev.finaxis.core.rails.security.SignatureVerifier.Verification;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class SignatureVerifierTest {

    /** test-key-ed25519 of RFC 9421, appendix B.1.4: the public half. */
    private static final String RFC_PUBLIC_KEY = "MCowBQYDK2VwAyEAJrQLj5P/89iXES9+vFgrIy29clF9CC/oPPsw3c5D0bs=";

    private static final String PATH = "/rails/pix/credits";
    private static final String JSON = "application/json";
    private static final byte[] BODY = "{\"messageType\":\"pacs.008\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant NOW = Instant.parse("2026-11-21T14:30:12Z");

    private final RailsSigner current = new RailsSigner("rails-2026-11");
    private final RailsSigner previous = new RailsSigner("rails-2026-10");
    private final MutableClock clock = new MutableClock(NOW);
    private final SignatureVerifier verifier = new SignatureVerifier(
            Map.of(current.keyId(), publicKey(current.publicKey()), previous.keyId(), publicKey(previous.publicKey())),
            clock);

    @Test
    void verifiesTheEd25519ExampleOfRfc9421() {
        String base = SignatureVerifier.signatureBase(
                List.of(
                        Map.entry("date", "Tue, 20 Apr 2021 02:07:55 GMT"),
                        Map.entry("@method", "POST"),
                        Map.entry("@path", "/foo"),
                        Map.entry("@authority", "example.com"),
                        Map.entry("content-type", "application/json"),
                        Map.entry("content-length", "18")),
                "(\"date\" \"@method\" \"@path\" \"@authority\" \"content-type\" \"content-length\")"
                        + ";created=1618884473;keyid=\"test-key-ed25519\"");
        byte[] signature = Base64.getDecoder()
                .decode("wqcAqbmYJ2ji2glfAMaRy4gruYYnx2nEFN2HN6jrnDnQCK1u02Gb04v9EDgwUPiu4A0w6vuQv5lIp5WPpBKRCw==");

        assertThat(SignatureVerifier.ed25519(publicKey(RFC_PUBLIC_KEY), base, signature))
                .isTrue();
    }

    @Test
    void verifiesARequestTheRailsSigned() {
        var request = signed(current, "POST", PATH, BODY, NOW);

        assertThat(verifier.verify(request, BODY)).isEqualTo(new Verification.Verified("rails-2026-11"));
    }

    // During a rotation both keys are configured, and a delivery signed with the old one still verifies.
    @Test
    void verifiesARequestSignedWithEitherConfiguredKey() {
        var request = signed(previous, "POST", PATH, BODY, NOW);

        assertThat(verifier.verify(request, BODY)).isEqualTo(new Verification.Verified("rails-2026-10"));
    }

    @Test
    void refusesARequestWithoutASignature() {
        var request = new MockHttpServletRequest("POST", PATH);
        request.setContentType(JSON);

        assertRefused(request, BODY, Refusal.MISSING_HEADER);
    }

    @Test
    void refusesABodyThatIsNotTheOneSigned() {
        var request = signed(current, "POST", PATH, BODY, NOW);
        byte[] changed = "{\"messageType\":\"pacs.009\"}".getBytes(StandardCharsets.UTF_8);

        assertRefused(request, changed, Refusal.DIGEST_MISMATCH);
    }

    @Test
    void refusesADigestReplacedToMatchAChangedBody() {
        byte[] changed = "{\"messageType\":\"pacs.009\"}".getBytes(StandardCharsets.UTF_8);
        var request = signed(current, "POST", PATH, BODY, NOW);
        request.removeHeader(SignatureVerifier.CONTENT_DIGEST);
        request.addHeader(
                SignatureVerifier.CONTENT_DIGEST,
                current.sign("POST", PATH, JSON, changed, NOW).contentDigest());

        assertRefused(request, changed, Refusal.BAD_SIGNATURE);
    }

    @Test
    void refusesARequestSentToAnotherPath() {
        var headers = current.sign("POST", "/rails/pix/refunds", JSON, BODY, NOW);

        assertRefused(request("POST", PATH, JSON, headers), BODY, Refusal.BAD_SIGNATURE);
    }

    @Test
    void refusesARequestWithAnotherMethod() {
        var headers = current.sign("PUT", PATH, JSON, BODY, NOW);

        assertRefused(request("POST", PATH, JSON, headers), BODY, Refusal.BAD_SIGNATURE);
    }

    @Test
    void refusesARequestWithAnotherContentType() {
        var headers = current.sign("POST", PATH, JSON, BODY, NOW);

        assertRefused(request("POST", PATH, "text/plain", headers), BODY, Refusal.BAD_SIGNATURE);
    }

    @Test
    void refusesAKeyIdTheCoreIsNotConfiguredWith() {
        var stranger = new RailsSigner("rails-2027-01");

        assertRefused(signed(stranger, "POST", PATH, BODY, NOW), BODY, Refusal.UNKNOWN_KEY);
    }

    // The key ID chooses the key, so naming another known key makes the signature fail.
    @Test
    void refusesASignatureThatNamesAnotherKnownKey() {
        var headers = current.sign("POST", PATH, JSON, BODY, NOW);
        var renamed = new RailsSigner.Headers(
                headers.contentDigest(),
                headers.signatureInput().replace("rails-2026-11", "rails-2026-10"),
                headers.signature());

        assertRefused(request("POST", PATH, JSON, renamed), BODY, Refusal.BAD_SIGNATURE);
    }

    @Test
    void refusesASignatureOverOtherComponents() {
        var headers = current.sign("POST", PATH, JSON, BODY, NOW);
        var fewer = new RailsSigner.Headers(
                headers.contentDigest(),
                headers.signatureInput().replace(" \"content-type\"", ""),
                headers.signature());

        assertRefused(request("POST", PATH, JSON, fewer), BODY, Refusal.UNSUPPORTED_SIGNATURE);
    }

    @Test
    void refusesAnotherAlgorithm() {
        var headers = current.sign("POST", PATH, JSON, BODY, NOW);
        var other = new RailsSigner.Headers(
                headers.contentDigest(),
                headers.signatureInput().replace("ed25519", "rsa-pss-sha512"),
                headers.signature());

        assertRefused(request("POST", PATH, JSON, other), BODY, Refusal.UNSUPPORTED_SIGNATURE);
    }

    @Test
    void acceptsASignatureFiveMinutesOld() {
        var request = signed(current, "POST", PATH, BODY, NOW.minus(SignatureVerifier.MAX_AGE));

        assertThat(verifier.verify(request, BODY)).isInstanceOf(Verification.Verified.class);
    }

    @Test
    void refusesASignatureOlderThanFiveMinutes() {
        var request = signed(current, "POST", PATH, BODY, NOW);
        clock.advance(SignatureVerifier.MAX_AGE.plusSeconds(1));

        assertRefused(request, BODY, Refusal.STALE);
    }

    @Test
    void acceptsASignatureOneMinuteAhead() {
        var request = signed(current, "POST", PATH, BODY, NOW.plus(SignatureVerifier.MAX_AHEAD));

        assertThat(verifier.verify(request, BODY)).isInstanceOf(Verification.Verified.class);
    }

    @Test
    void refusesASignatureMoreThanOneMinuteAhead() {
        var request = signed(current, "POST", PATH, BODY, NOW.plus(Duration.ofSeconds(61)));

        assertRefused(request, BODY, Refusal.FROM_THE_FUTURE);
    }

    private void assertRefused(MockHttpServletRequest request, byte[] body, Refusal reason) {
        assertThat(verifier.verify(request, body)).isEqualTo(new Verification.Refused(reason));
    }

    private static MockHttpServletRequest signed(
            RailsSigner signer, String method, String path, byte[] body, Instant created) {
        return request(method, path, JSON, signer.sign(method, path, JSON, body, created));
    }

    private static MockHttpServletRequest request(
            String method, String path, String contentType, RailsSigner.Headers headers) {
        var request = new MockHttpServletRequest(method, path);
        request.setContentType(contentType);
        request.addHeader(SignatureVerifier.CONTENT_DIGEST, headers.contentDigest());
        request.addHeader(SignatureVerifier.SIGNATURE_INPUT, headers.signatureInput());
        request.addHeader(SignatureVerifier.SIGNATURE, headers.signature());
        return request;
    }

    private static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
