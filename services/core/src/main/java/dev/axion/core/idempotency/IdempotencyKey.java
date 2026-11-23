package dev.axion.core.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The key a client sends in {@code Idempotency-Key} to name one logical operation (idempotency
 * design, section 3.2): 1 to 255 visible ASCII characters, compared as opaque text. A key belongs to
 * the tenant that sent it.
 */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 255;

    // The key is text the client chose, so it is never echoed back in the message.
    public IdempotencyKey {
        if (value.isEmpty() || value.length() > MAX_LENGTH || !isVisibleAscii(value)) {
            throw new IllegalArgumentException(
                    "An idempotency key is 1 to " + MAX_LENGTH + " visible ASCII characters");
        }
    }

    /** SHA-256 of the key in hex: what logs carry instead of the key (section 7). */
    public String hash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The hash, never the key, so a key printed by mistake reveals nothing the client put in it. */
    @Override
    public String toString() {
        return "IdempotencyKey[sha256=" + hash() + "]";
    }

    private static boolean isVisibleAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
