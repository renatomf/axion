package dev.axion.core.rails.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

/** The wait between two attempts to hand a credit over (billing design, section 7.3). */
class CreditProcessorBackoffTest {

    @Property
    void staysWithinOneSecondTimesTwoToTheAttemptsAndFiveMinutes(
            @ForAll @IntRange(min = 1, max = 10_000) int attempt, @ForAll long seed) {
        Duration delay = CreditProcessor.backoff(attempt, new SplittableRandom(seed));

        long ceiling = Math.min(Duration.ofMinutes(5).toMillis(), 1000L * (1L << Math.min(attempt - 1, 20)));
        assertThat(delay).isBetween(Duration.ZERO, Duration.ofMillis(ceiling));
    }

    @Test
    void waitsAtMostASecondAfterTheFirstFailure() {
        var random = new SplittableRandom(1);
        for (int i = 0; i < 1_000; i++) {
            assertThat(CreditProcessor.backoff(1, random)).isLessThanOrEqualTo(Duration.ofSeconds(1));
        }
    }

    @Test
    void reachesTheFiveMinuteCeilingOnlyFromTheTenthFailure() {
        assertThat(CreditProcessor.backoff(9, new MaxRandom())).isEqualTo(Duration.ofSeconds(256));
        assertThat(CreditProcessor.backoff(10, new MaxRandom())).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void refusesAnAttemptBeforeTheFirst() {
        assertThatThrownBy(() -> CreditProcessor.backoff(0, new SplittableRandom(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Always draws the largest value allowed. */
    private static final class MaxRandom implements RandomGenerator {

        @Override
        public long nextLong() {
            return Long.MAX_VALUE;
        }

        @Override
        public long nextLong(long bound) {
            return bound - 1;
        }
    }
}
