package dev.finaxis.core.shared;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The identifier of a resource as the API shows it: a prefix naming the type of resource, an
 * underscore and the resource's UUID in 26 characters, such as {@code ch_01h455vb4pex5vsknk084sn02q}.
 *
 * <p>The prefix tells a reader, a log search or a support agent what an ID refers to, and lets the
 * API reject an ID of the wrong type, such as a payout ID sent where a charge ID is expected. The
 * text is the UUID itself, encoded, not a second identifier: the database keeps only the UUID, and
 * the public ID is derived from it and parsed back to it.
 *
 * <p>The format is the one of <a href="https://github.com/jetify-com/typeid">TypeID</a>: the 128 bits
 * of the UUID, most significant first, in lowercase Crockford base32. The alphabet has no {@code i},
 * {@code l}, {@code o} or {@code u}, so an ID read aloud or copied by hand is not mistaken for
 * another. The encoding preserves order: public IDs sort as text in the same order as their UUIDs,
 * that is, by creation time for UUIDv7.
 *
 * <p>A UUIDv7 carries its creation time to the millisecond, so a public ID reveals when the resource
 * was created; the API returns that time anyway.
 *
 * @param prefix the resource type, 1 to 8 lowercase ASCII letters, such as {@code ch} for a charge
 * @param uuid the resource's primary key
 */
public record PublicId(String prefix, UUID uuid) {

    private static final Pattern PREFIX = Pattern.compile("[a-z]{1,8}");
    private static final String ALPHABET = "0123456789abcdefghjkmnpqrstvwxyz";
    private static final int ENCODED_LENGTH = 26;

    /** Rejects a prefix that would make the ID ambiguous or hard to read. */
    public PublicId {
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Prefix must be 1 to 8 lowercase letters: " + prefix);
        }
    }

    /**
     * Parses a public ID that must have the expected prefix.
     *
     * <p>Parsing is strict, because the text comes from API clients: only the exact form {@link
     * #toString} produces is accepted, so each resource has a single spelling. Uppercase letters, the
     * look-alike letters Crockford base32 allows elsewhere, extra characters and values above 128 bits
     * are all rejected.
     *
     * @throws IllegalArgumentException if the text is not a public ID with the expected prefix
     */
    public static PublicId parse(String expectedPrefix, String text) {
        // The length is checked first, so an oversized input is rejected without being scanned.
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

    /** The public ID as text, such as {@code ch_01h455vb4pex5vsknk084sn02q}. */
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
