package dev.finaxis.core.access.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.shared.Environment;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.CharRange;
import net.jqwik.api.constraints.IntRange;

/**
 * Properties of every API key (tenancy design, section 8). Keys come from a {@code SHA1PRNG} seeded
 * by jqwik, so a failing key can be reproduced from the reported seed.
 */
class ApiKeySecretPropertiesTest {

    @Property(tries = 1000)
    void everyGeneratedKeyPassesItsChecksum(@ForAll long seed, @ForAll Environment environment) {
        ApiKeySecret key = ApiKeySecret.generate(environment, seeded(seed));

        assertThat(ApiKeySecret.parse(key.reveal()))
                .hasValueSatisfying(parsed -> assertThat(parsed.environment()).isEqualTo(environment));
    }

    /**
     * A CRC32 detects every error confined to 32 consecutive bits, so a change to any one character of
     * the random part always changes the checksum; a change to the prefix or the checksum itself is
     * caught by the comparison. The replacement is any printable ASCII character, base62 or not.
     */
    @Property(tries = 2000)
    void changingAnyOneCharacterFailsTheKey(
            @ForAll long seed,
            @ForAll Environment environment,
            @ForAll @IntRange(min = 0, max = 43) int position,
            @ForAll @CharRange(from = ' ', to = '~') char replacement) {
        String text = ApiKeySecret.generate(environment, seeded(seed)).reveal();
        Assume.that(text.charAt(position) != replacement);

        String changed = text.substring(0, position) + replacement + text.substring(position + 1);

        assertThat(ApiKeySecret.parse(changed)).isEmpty();
    }

    private static SecureRandom seeded(long seed) {
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed(seed);
            return random;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
