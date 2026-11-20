package dev.finaxis.core.shared;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A resource's ID as the API shows it, such as {@code ch_01h455vb4pex5vsknk084sn02q}: a type prefix
 * and the UUID itself in TypeID's Crockford base32, which sorts in the UUID's order. The prefix
 * lets the API reject an ID of the wrong type.
 *
 * @param prefix 1 to 8 lowercase ASCII letters
 */
public record PublicId(String prefix, UUID uuid) {

    private static final Pattern PREFIX = Pattern.compile("[a-z]{1,8}");
    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final int ENCODED_LENGTH = 26;

    public PublicId {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Prefix must be 1 to 8 lowercase letters: " + prefix);
        }
    }

    /**
     * Strict, since the text comes from clients: only the form {@link #toString} produces, so each
     * resource has one spelling.
     *
     * @throws IllegalArgumentException if the text is not a public ID with the expected prefix
     */
    public static PublicId parse(String expectedPrefix, String text) {
        // Length first, so an oversized input is never scanned.
        if (text.length() != expectedPrefix.length() + 1 + ENCODED_LENGTH || !text.startsWith(expectedPrefix + "_")) {
            throw invalid(expectedPrefix, text);
        }
        String encoded = text.substring(expectedPrefix.length() + 1);
        // 26 characters of 5 bits are 130 bits; the first character must leave the top 2 at zero.
        if (encoded.charAt(0) > '7') {
            throw invalid(expectedPrefix, text);
        }
        long mostSignificant = 0;
        long leastSignificant = 0;
        for (int i = 0; i < ENCODED_LENGTH; i++) {
            int value = ALPHABET.indexOf(encoded.charAt(i));
            if (value < 0) {
                throw invalid(expectedPrefix, text);
            }
            // Shift the 128-bit number, held in two longs, left by 5 bits and add the new digit.
            mostSignificant = (mostSignificant << 5) | (leastSignificant >>> 59);
            leastSignificant = (leastSignificant << 5) | value;
        }
        return new PublicId(expectedPrefix, new UUID(mostSignificant, leastSignificant));
    }

    @Override
    public String toString() {
        char[] encoded = new char[ENCODED_LENGTH];
        long mostSignificant = uuid.getMostSignificantBits();
        long leastSignificant = uuid.getLeastSignificantBits();
        // From the last character to the first: take the lowest 5 bits, then shift the number right.
        for (int i = ENCODED_LENGTH - 1; i >= 0; i--) {
            encoded[i] = ALPHABET.charAt((int) (leastSignificant & 0b11111));
            leastSignificant = (leastSignificant >>> 5) | (mostSignificant << 59);
            mostSignificant >>>= 5;
        }
        return prefix + "_" + new String(encoded);
    }

    private static IllegalArgumentException invalid(String expectedPrefix, String text) {
        // The rejected text is not echoed: it is client input and may be arbitrarily long.
        return new IllegalArgumentException(
                "Not a public ID with prefix " + expectedPrefix + " (length " + text.length() + ")");
    }
}
