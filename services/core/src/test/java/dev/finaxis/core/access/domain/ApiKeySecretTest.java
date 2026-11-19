package dev.finaxis.core.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.shared.Environment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Keys are generated at run time: no text that looks like a real key is written in this repository,
 * so the secret scanners can stay strict.
 */
class ApiKeySecretTest {

    private final SecureRandom random = new SecureRandom();

    @ParameterizedTest
    @EnumSource(Environment.class)
    void startsWithThePrefixOfItsEnvironment(Environment environment) {
        ApiKeySecret key = ApiKeySecret.generate(environment, random);

        assertThat(key.environment()).isEqualTo(environment);
        assertThat(key.reveal()).startsWith(ApiKeySecret.prefix(environment));
    }

    @Test
    void prefixesNameTheEnvironment() {
        assertThat(ApiKeySecret.prefix(Environment.TEST)).isEqualTo("sk_test_");
        assertThat(ApiKeySecret.prefix(Environment.LIVE)).isEqualTo("sk_live_");
    }

    @Test
    void hasThirtyRandomAndSixChecksumCharactersInBase62() {
        String text = ApiKeySecret.generate(Environment.TEST, random).reveal();

        assertThat(text.substring("sk_test_".length())).matches("[0-9A-Za-z]{36}");
    }

    @Test
    void endsWithTheChecksumOfItsRandomPart() {
        String text = ApiKeySecret.generate(Environment.LIVE, random).reveal();
        String randomPart = text.substring("sk_live_".length(), "sk_live_".length() + 30);

        assertThat(text).endsWith(ApiKeySecret.checksum(randomPart));
    }

    @Test
    void twoKeysAreDifferent() {
        assertThat(ApiKeySecret.generate(Environment.TEST, random).reveal())
                .isNotEqualTo(ApiKeySecret.generate(Environment.TEST, random).reveal());
    }

    @ParameterizedTest
    @EnumSource(Environment.class)
    void parsesAGeneratedKeyBackToItsEnvironment(Environment environment) {
        String text = ApiKeySecret.generate(environment, random).reveal();

        assertThat(ApiKeySecret.parse(text)).hasValueSatisfying(key -> {
            assertThat(key.environment()).isEqualTo(environment);
            assertThat(key.reveal()).isEqualTo(text);
        });
    }

    @Test
    void rejectsAKeyWithAWrongChecksum() {
        String text = ApiKeySecret.generate(Environment.TEST, random).reveal();
        char last = text.charAt(text.length() - 1);
        String mistyped = text.substring(0, text.length() - 1) + (last == 'a' ? 'b' : 'a');

        assertThat(ApiKeySecret.parse(mistyped)).isEmpty();
    }

    @Test
    void rejectsAKeyWithAnUnknownEnvironment() {
        String text = ApiKeySecret.generate(Environment.LIVE, random).reveal();

        assertThat(ApiKeySecret.parse(text.replace("sk_live_", "sk_prod_"))).isEmpty();
    }

    /** A key is case-sensitive: base62 tells {@code a} from {@code A}. */
    @Test
    void rejectsAKeyInAnotherCase() {
        String text = ApiKeySecret.generate(Environment.TEST, random).reveal();

        String body = text.substring("sk_test_".length());

        assertThat(ApiKeySecret.parse("sk_test_" + body.toUpperCase(Locale.ROOT)))
                .isEmpty();
        assertThat(ApiKeySecret.parse("SK_TEST_" + body)).isEmpty();
    }

    @Test
    void rejectsAKeyWithSurroundingText() {
        String text = ApiKeySecret.generate(Environment.TEST, random).reveal();

        assertThat(ApiKeySecret.parse(" " + text)).isEmpty();
        assertThat(ApiKeySecret.parse(text + "\n")).isEmpty();
        assertThat(ApiKeySecret.parse(text + "0")).isEmpty();
        assertThat(ApiKeySecret.parse(text.substring(0, text.length() - 1))).isEmpty();
    }

    @Test
    void rejectsACharacterOutsideBase62() {
        String text = ApiKeySecret.generate(Environment.TEST, random).reveal();

        assertThat(ApiKeySecret.parse(text.substring(0, 10) + "-" + text.substring(11)))
                .isEmpty();
        assertThat(ApiKeySecret.parse(text.substring(0, 10) + "é" + text.substring(11)))
                .isEmpty();
    }

    @Test
    void rejectsTextThatIsNotAKey() {
        assertThat(ApiKeySecret.parse("")).isEmpty();
        assertThat(ApiKeySecret.parse("sk_test_")).isEmpty();
        assertThat(ApiKeySecret.parse("not a key")).isEmpty();
    }

    @Test
    void hashIsTheSha256OfTheWholeKey() throws NoSuchAlgorithmException {
        ApiKeySecret key = ApiKeySecret.generate(Environment.TEST, random);
        byte[] expected =
                MessageDigest.getInstance("SHA-256").digest(key.reveal().getBytes(StandardCharsets.US_ASCII));

        assertThat(key.hash()).hasSize(32).isEqualTo(expected);
    }

    @Test
    void theSameKeyParsedAgainHasTheSameHash() {
        ApiKeySecret key = ApiKeySecret.generate(Environment.LIVE, random);

        assertThat(ApiKeySecret.parse(key.reveal()))
                .hasValueSatisfying(parsed -> assertThat(parsed.hash()).isEqualTo(key.hash()));
    }

    @Test
    void changingTheReturnedHashDoesNotChangeTheKey() {
        ApiKeySecret key = ApiKeySecret.generate(Environment.TEST, random);
        byte[] hash = key.hash();
        byte[] original = hash.clone();

        hash[0] ^= 1;

        assertThat(key.hash()).isEqualTo(original);
    }

    @Test
    void lastFourAreTheLastFourCharacters() {
        ApiKeySecret key = ApiKeySecret.generate(Environment.TEST, random);

        assertThat(key.lastFour()).hasSize(4);
        assertThat(key.reveal()).endsWith(key.lastFour());
    }

    @Test
    void toStringMasksTheSecret() {
        ApiKeySecret key = ApiKeySecret.generate(Environment.LIVE, random);

        assertThat(key).hasToString("sk_live_…" + key.lastFour());
        assertThat(key.toString()).doesNotContain(key.reveal().substring("sk_live_".length(), 40));
    }

    /**
     * The CRC32 of "123456789" is the standard check value of the algorithm, 0xCBF43926 (3421780262),
     * which is "3jZRME" in base62; an empty input has a CRC32 of zero, padded to six characters.
     */
    @Test
    void checksumIsTheCrc32InSixBase62Characters() {
        assertThat(ApiKeySecret.checksum("123456789")).isEqualTo("3jZRME");
        assertThat(ApiKeySecret.checksum("")).isEqualTo("000000");
    }
}
