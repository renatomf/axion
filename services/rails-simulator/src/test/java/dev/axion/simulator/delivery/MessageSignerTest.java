package dev.axion.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.simulator.delivery.MessageSigner.SignatureHeaders;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MessageSignerTest {

    /** test-key-ed25519 of RFC 9421, appendix B.1.4. */
    private static final String RFC_PRIVATE_KEY = "MC4CAQAwBQYDK2VwBCIEIJ+DYvh6SEqVTm50DFtMDoQikTmiCqirVv9mWG9qfSnF";

    private static final byte[] BODY = "{\"hello\": \"world\"}".getBytes(StandardCharsets.UTF_8);
    private static final Instant CREATED = Instant.ofEpochSecond(1_795_271_412);

    private final KeyPair pair = generate();
    private final MessageSigner signer = new MessageSigner("rails-2026-11", pair.getPrivate());

    @Test
    void reproducesTheEd25519ExampleOfRfc9421() throws GeneralSecurityException {
        var rfcSigner = new MessageSigner("test-key-ed25519", rfcKey());
        String base = MessageSigner.signatureBase(
                List.of(
                        Map.entry("date", "Tue, 20 Apr 2021 02:07:55 GMT"),
                        Map.entry("@method", "POST"),
                        Map.entry("@path", "/foo"),
                        Map.entry("@authority", "example.com"),
                        Map.entry("content-type", "application/json"),
                        Map.entry("content-length", "18")),
                MessageSigner.parameters(
                        List.of("date", "@method", "@path", "@authority", "content-type", "content-length"),
                        1_618_884_473,
                        "test-key-ed25519"));

        assertThat(Base64.getEncoder().encodeToString(rfcSigner.sign(base)))
                .isEqualTo("wqcAqbmYJ2ji2glfAMaRy4gruYYnx2nEFN2HN6jrnDnQCK1u02Gb04v9EDgwUPiu4A0w6vuQv5lIp5WPpBKRCw==");
    }

    @Test
    void reproducesTheSha256ExampleOfRfc9530() {
        byte[] withNewline = "{\"hello\": \"world\"}\n".getBytes(StandardCharsets.UTF_8);

        assertThat(MessageSigner.contentDigest(withNewline))
                .isEqualTo("sha-256=:RK/0qy18MlBSVnWgjwz6lZEWjP/lF5HF9bvEF8FabDg=:");
    }

    @Test
    void namesTheCoveredComponentsTheTimeAndTheKey() {
        SignatureHeaders headers = signer.sign("POST", "/rails/pix/credits", "application/json", BODY, CREATED);

        assertThat(headers.signatureInput())
                .isEqualTo("rails=(\"@method\" \"@path\" \"content-type\" \"content-digest\");created=1795271412"
                        + ";keyid=\"rails-2026-11\";alg=\"ed25519\"");
        assertThat(headers.contentDigest()).isEqualTo(MessageSigner.contentDigest(BODY));
        assertThat(headers.signature()).matches("rails=:[A-Za-z0-9+/]{86}==:");
    }

    @Test
    void signsWhatTheCoreVerifies() throws GeneralSecurityException {
        SignatureHeaders headers = signer.sign("POST", "/rails/pix/credits", "application/json", BODY, CREATED);

        assertThat(verifies(headers, "POST", "/rails/pix/credits", BODY)).isTrue();
    }

    @Test
    void breaksWhenTheBodyThePathOrTheMethodChanges() throws GeneralSecurityException {
        SignatureHeaders headers = signer.sign("POST", "/rails/pix/credits", "application/json", BODY, CREATED);
        byte[] tampered = "{\"hello\": \"World\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(verifies(headers, "POST", "/rails/pix/credits", tampered)).isFalse();
        assertThat(verifies(headers, "POST", "/rails/pix/refunds", BODY)).isFalse();
        assertThat(verifies(headers, "PUT", "/rails/pix/credits", BODY)).isFalse();
    }

    @Test
    void refusesAKeyIdThatWouldNeedEscaping() {
        assertThatThrownBy(() -> new MessageSigner("rails\"2026", pair.getPrivate()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesAPrivateKeyThatIsNotEd25519WithoutPrintingIt() {
        assertThatThrownBy(() -> new MessageSigner(new SigningProperties("rails-2026-11", "bm90LWEta2V5")))
                .isInstanceOf(IllegalArgumentException.class)
                .message()
                .doesNotContain("bm90LWEta2V5");
    }

    /** What the core will do (PR 1.38a): recompute the digest and the signature base, then verify. */
    private boolean verifies(SignatureHeaders headers, String method, String path, byte[] body)
            throws GeneralSecurityException {
        String parameters = headers.signatureInput().substring("rails=".length());
        String base = MessageSigner.signatureBase(
                List.of(
                        Map.entry("@method", method),
                        Map.entry("@path", path),
                        Map.entry("content-type", "application/json"),
                        Map.entry("content-digest", MessageSigner.contentDigest(body))),
                parameters);
        String signature = headers.signature()
                .substring("rails=:".length(), headers.signature().length() - 1);
        return verify(pair.getPublic(), base, Base64.getDecoder().decode(signature));
    }

    static boolean verify(PublicKey key, String base, byte[] signature) throws GeneralSecurityException {
        Signature ed25519 = Signature.getInstance("Ed25519");
        ed25519.initVerify(key);
        ed25519.update(base.getBytes(StandardCharsets.US_ASCII));
        return ed25519.verify(signature);
    }

    private static PrivateKey rfcKey() throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(RFC_PRIVATE_KEY)));
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
