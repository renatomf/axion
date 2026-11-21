package dev.finaxis.simulator.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Signs each request to the core under HTTP Message Signatures (RFC 9421) with Ed25519, over the method,
 * the path, the content type and the {@code Content-Digest} of the body (RFC 9530), as section 6.3 of the
 * rails simulator design specifies. The host is left out: proxies may rewrite it.
 */
@Component
@EnableConfigurationProperties(SigningProperties.class)
class MessageSigner {

    static final String LABEL = "rails";

    private static final List<String> COVERED = List.of("@method", "@path", "content-type", "content-digest");

    /** A token that needs no escaping inside the quoted {@code keyid} parameter. */
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private final String keyId;
    private final PrivateKey key;

    @Autowired
    MessageSigner(SigningProperties signing) {
        this(signing.keyId(), privateKey(signing.privateKey()));
    }

    MessageSigner(String keyId, PrivateKey key) {
        if (!KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException(
                    "simulator.signing.key-id must be 1 to 64 letters, digits, '.', '_' or '-'");
        }
        this.keyId = keyId;
        this.key = key;
    }

    /** The headers that sign a request: {@code Content-Digest}, {@code Signature-Input} and {@code Signature}. */
    record SignatureHeaders(String contentDigest, String signatureInput, String signature) {}

    SignatureHeaders sign(String method, String path, String contentType, byte[] body, Instant created) {
        String digest = contentDigest(body);
        String parameters = parameters(COVERED, created.getEpochSecond(), keyId) + ";alg=\"ed25519\"";
        String base = signatureBase(
                List.of(
                        Map.entry("@method", method),
                        Map.entry("@path", path),
                        Map.entry("content-type", contentType),
                        Map.entry("content-digest", digest)),
                parameters);
        return new SignatureHeaders(
                digest,
                LABEL + "=" + parameters,
                LABEL + "=:" + Base64.getEncoder().encodeToString(sign(base)) + ":");
    }

    /** The SHA-256 of the body, as a byte sequence of RFC 8941. */
    static String contentDigest(byte[] body) {
        try {
            byte[] sha256 = MessageDigest.getInstance("SHA-256").digest(body);
            return "sha-256=:" + Base64.getEncoder().encodeToString(sha256) + ":";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime has SHA-256", e);
        }
    }

    /** The {@code @signature-params} value: the covered components as an inner list, then the parameters. */
    static String parameters(List<String> covered, long created, String keyId) {
        String components = covered.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(" "));
        return "(" + components + ");created=" + created + ";keyid=\"" + keyId + "\"";
    }

    /** The signature base of RFC 9421, section 2.5: one line per component, then the parameters, no final newline. */
    static String signatureBase(List<Map.Entry<String, String>> components, String parameters) {
        var base = new StringBuilder();
        components.forEach(component -> base.append('"')
                .append(component.getKey())
                .append("\": ")
                .append(component.getValue())
                .append('\n'));
        return base.append("\"@signature-params\": ").append(parameters).toString();
    }

    byte[] sign(String signatureBase) {
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initSign(key);
            ed25519.update(signatureBase.getBytes(StandardCharsets.US_ASCII));
            return ed25519.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 signing failed", e);
        }
    }

    // The key is never part of an error message.
    private static PrivateKey privateKey(String base64) {
        try {
            var spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim()));
            return KeyFactory.getInstance("Ed25519").generatePrivate(spec);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new IllegalArgumentException(
                    "simulator.signing.private-key must be an Ed25519 private key, PKCS #8 DER in base64");
        }
    }
}
