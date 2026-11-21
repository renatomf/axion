package dev.finaxis.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Binds an idempotency key to one request: the SHA-256 of its path, a line feed and its body, as
 * received (idempotency design, section 3.3). Any difference in either, even in whitespace, is
 * another request.
 */
public final class Fingerprint {

    public static final int LENGTH = 32;

    private final byte[] value;

    public Fingerprint(byte[] value) {
        if (value.length != LENGTH) {
            throw new IllegalArgumentException("A fingerprint is " + LENGTH + " bytes: " + value.length);
        }
        this.value = value.clone();
    }

    public static Fingerprint of(String path, byte[] body) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(path.getBytes(StandardCharsets.UTF_8));
            sha256.update((byte) '\n');
            sha256.update(body);
            return new Fingerprint(sha256.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public byte[] value() {
        return value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Fingerprint fingerprint && Arrays.equals(value, fingerprint.value);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(value);
    }

    @Override
    public String toString() {
        return HexFormat.of().formatHex(value);
    }
}
