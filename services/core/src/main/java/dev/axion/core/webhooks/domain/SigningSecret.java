package dev.axion.core.webhooks.domain;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * The secret that signs an endpoint's requests (events and webhooks design, section 6.2): 32 random
 * bytes, shown to the tenant as {@code whsec_} and their base64, the format of Standard Webhooks.
 */
public final class SigningSecret {

    public static final String PREFIX = "whsec_";

    public static final int LENGTH = 32;

    private final byte[] key;

    private SigningSecret(byte[] key) {
        if (key.length != LENGTH) {
            throw new IllegalArgumentException("A signing secret is " + LENGTH + " bytes, not " + key.length);
        }
        this.key = key.clone();
    }

    public static SigningSecret generate(SecureRandom random) {
        byte[] key = new byte[LENGTH];
        random.nextBytes(key);
        return new SigningSecret(key);
    }

    /** The secret of these bytes, such as those an encrypted secret decrypts to. */
    public static SigningSecret of(byte[] key) {
        return new SigningSecret(key);
    }

    /** The bytes the signature's HMAC is keyed with. */
    public byte[] key() {
        return key.clone();
    }

    /** As the tenant receives it, once. */
    public String text() {
        return PREFIX + Base64.getEncoder().encodeToString(key);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SigningSecret secret && Arrays.equals(key, secret.key);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(key);
    }

    // Never the secret: a logged object must not leak it.
    @Override
    public String toString() {
        return "SigningSecret[…]";
    }
}
