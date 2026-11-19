package dev.finaxis.core.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.shared.Environment;
import java.security.SecureRandom;
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
