package dev.axion.core.access.domain;

import dev.axion.core.shared.Environment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.CRC32;

/**
 * An API key's secret, {@code sk_<environment>_<30 random base62><6 base62 CRC32>} (tenancy design,
 * section 4.3). About 178 random bits. The checksum is no security control: it rejects mistyped keys
 * without a query and lets secret scanners recognize real ones. The prefix is never trusted.
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

    /** A new key; {@code random} is a {@code SecureRandom}, so it cannot be predicted from earlier ones. */
    public static ApiKeySecret generate(Environment environment, SecureRandom random) {
        StringBuilder randomPart = new StringBuilder(RANDOM_LENGTH);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            randomPart.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        String randomText = randomPart.toString();
        return new ApiKeySecret(environment, prefix(environment) + randomText + checksum(randomText));
    }

    /** Empty for any text that is not a key, found without a query. The length is checked first. */
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

    public Environment environment() {
        return environment;
    }

    /** The whole key, shown once to its issuer; the database keeps only the hash. */
    public String reveal() {
        return text;
    }

    /**
     * SHA-256 of the whole key. A fast hash suits a random 178-bit secret: slow hashes such as Argon2
     * protect guessable passwords, and would add tens of milliseconds to every request.
     */
    public byte[] hash() {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String lastFour() {
        return text.substring(text.length() - 4);
    }

    /** Masked, such as {@code sk_live_…a1b2}, so a key printed by mistake does not leak. */
    @Override
    public String toString() {
        return prefix(environment) + "…" + lastFour();
    }

    static String prefix(Environment environment) {
        return "sk_" + environment.name().toLowerCase(Locale.ROOT) + "_";
    }

    private static boolean isBase62(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // ASCII only: Character.isLetterOrDigit also accepts non-ASCII letters.
            boolean base62 = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            if (!base62) {
                return false;
            }
        }
        return true;
    }

    // 62^6 is about 5.7 × 10^10, so 6 characters always hold a 32-bit CRC.
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
