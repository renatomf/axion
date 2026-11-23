package dev.finaxis.core.rails.security;

/**
 * A request body whose rails signature verified, and the key that signed it. The filter chain
 * stores it under {@link #ATTRIBUTE}; the endpoint reads the body from here, never from the
 * request, so it handles only the bytes that were verified.
 */
public final class SignedMessage {

    public static final String ATTRIBUTE = SignedMessage.class.getName();

    private final String keyId;
    private final byte[] body;
    private final byte[] digest;

    SignedMessage(String keyId, byte[] body) {
        this.keyId = keyId;
        this.body = body.clone();
        this.digest = SignatureVerifier.sha256(body);
    }

    /** The {@code keyid} of the key that verified the signature. */
    public String keyId() {
        return keyId;
    }

    public byte[] body() {
        return body.clone();
    }

    /** The SHA-256 of the body, the evidence of which bytes were accepted. */
    public byte[] digest() {
        return digest.clone();
    }
}
