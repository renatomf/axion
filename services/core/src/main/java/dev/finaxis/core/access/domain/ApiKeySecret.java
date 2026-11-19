package dev.finaxis.core.access.domain;

import dev.finaxis.core.shared.Environment;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * The secret of an API key, the text a tenant's system sends on {@code /v1} (tenancy and access
 * design, section 4.3):
 *
 * <pre>
 * sk_&lt;environment&gt;_&lt;30 random characters&gt;&lt;6 checksum characters&gt;
 * </pre>
 *
 * <p>Every character after the prefix is base62 ({@code 0-9}, {@code A-Z}, {@code a-z}), so a key
 * can be copied with a double click and needs no escaping in a header or a shell.
 *
 * <ul>
 *   <li>The 30 random characters come from a {@link SecureRandom}: 30 × log2(62), about 178 bits,
 *       beyond any brute force.
 *   <li>The checksum is the CRC32 of the random part, in 6 base62 characters, as in GitHub's token
 *       format. It is not a security control: anyone can compute it. It lets the core reject a
 *       mistyped or invented key without a database query, and lets a secret scanner tell a real key
 *       from text that only looks like one.
 *   <li>The environment in the prefix tells a reader which environment a leaked key reaches. The core
 *       never trusts it: the tenant of a key is read from the stored key.
 * </ul>
 */
public final class ApiKeySecret {

    static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static final int RANDOM_LENGTH = 30;
    static final int CHECKSUM_LENGTH = 6;

    private final Environment environment;
    private final String text;

    private ApiKeySecret(Environment environment, String text) {
        this.environment = environment;
        this.text = text;
    }

    /**
     * A new, random key for the environment.
     *
     * @param random the source of the random characters; a {@code SecureRandom}, so that the key
     *     cannot be predicted from keys issued before it
     */
    public static ApiKeySecret generate(Environment environment, SecureRandom random) {
        StringBuilder randomPart = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            // nextInt(bound) is uniform over [0, bound): every character is equally likely.
            randomPart.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String randomText = randomPart.toString();
        return new ApiKeySecret(environment, prefix(environment) + randomText + checksum(randomText));
    }

    /**
     * Reads a key a caller sent, if it has the exact form {@link #generate} produces and a valid
     * checksum.
     *
     * <p>An empty result is not an error: it is the answer for any text that is not a key, a mistyped
     * or invented one included, found without a database query. The text comes from the network and
     * may be anything, so its length is checked before any character is read.
     */
    public static Optional<ApiKeySecret> parse(String text) {
        for (Environment environment : Environment.values()) {
            String prefix = prefix(environment);
            if (text.length() == prefix.length() + RANDOM_LENGTH + CHECKSUM_LENGTH && text.startsWith(prefix)) {
                String body = text.substring(prefix.length());
                if (!isBase62(body)) {
                    return Optional.empty();
                }
                String randomPart = body.substring(0, RANDOM_LENGTH);
                String checksum = body.substring(RANDOM_LENGTH);
                return checksum.equals(checksum(randomPart))
                        ? Optional.of(new ApiKeySecret(environment, text))
                        : Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** The environment the prefix names. */
    public Environment environment() {
        return environment;
    }

    /**
     * The whole key, to be shown once to the member who issued it and never again. Nothing else in the
     * core should need it: the database keeps the hash.
     */
    public String reveal() {
        return text;
    }

    /** The prefix of a key for the environment, such as {@code sk_test_}. */
    static String prefix(Environment environment) {
        return "sk_" + environment.name().toLowerCase(Locale.ROOT) + "_";
    }

    private static boolean isBase62(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // Plain ASCII ranges: Character.isLetterOrDigit would also accept non-ASCII letters.
            boolean base62 = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!base62) {
                return false;
            }
        }
        return true;
    }

    /**
     * The CRC32 of the random part, as 6 base62 characters, most significant first. A CRC32 is at
     * most 2^32 - 1, and 62^6 is about 5.7 × 10^10, so 6 characters always fit it.
     */
    static String checksum(String randomPart) {
        CRC32 crc = new CRC32();
        crc.update(randomPart.getBytes(StandardCharsets.US_ASCII));
        long value = crc.getValue();
        char[] digits = new char[CHECKSUM_LENGTH];
        for (int i = CHECKSUM_LENGTH - 1; i >= 0; i--) {
            digits[i] = ALPHABET.charAt((int) (value % ALPHABET.length()));
            value /= ALPHABET.length();
        }
        return new String(digits);
    }
}
