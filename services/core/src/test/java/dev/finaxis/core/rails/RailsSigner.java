package dev.finaxis.core.rails;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/**
 * Signs a request as the rails simulator does (rails simulator design, section 6.3), with a key
 * pair of its own. Written apart from the core's verifier, so a mistake in one is not repeated by
 * the other.
 */
public final class RailsSigner {

    /** The headers that sign a request. */
    public record Headers(String contentDigest, String signatureInput, String signature) {}

    private final String keyId;
    private final KeyPair pair;

    public RailsSigner(String keyId) {
        this.keyId = keyId;
        try {
            this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String keyId() {
        return keyId;
    }

    /** The public key, X.509 SubjectPublicKeyInfo DER in base64, as the core is configured with it. */
    public String publicKey() {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    public Headers sign(String method, String path, String contentType, byte[] body, Instant created) {
        String digest = "sha-256=:" + Base64.getEncoder().encodeToString(sha256(body)) + ":";
        String parameters = "(\"@method\" \"@path\" \"content-type\" \"content-digest\");created="
                + created.getEpochSecond() + ";keyid=\"" + keyId + "\";alg=\"ed25519\"";
        String base = "\"@method\": " + method + "\n"
                + "\"@path\": " + path + "\n"
                + "\"content-type\": " + contentType + "\n"
                + "\"content-digest\": " + digest + "\n"
                + "\"@signature-params\": " + parameters;
        return new Headers(digest, "rails=" + parameters, "rails=:" + sign(base) + ":");
    }

    private String sign(String base) {
        try {
            Signature ed25519 = Signature.getInstance("Ed25519");
            ed25519.initSign(pair.getPrivate());
            ed25519.update(base.getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(ed25519.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
