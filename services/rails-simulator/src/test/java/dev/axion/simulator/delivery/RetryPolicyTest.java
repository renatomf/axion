package dev.axion.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.SplittableRandom;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RetryPolicyTest {

    private static final Instant CREATED = Instant.parse("2026-11-21T14:30:12.345Z");

    @Property
    void drawsADelayBetweenZeroAndTheCapOfTheAttempt(
            @ForAll @IntRange(min = 1, max = 1_000) int attempt, @ForAll long seed) {
        var policy = new RetryPolicy(new SplittableRandom(seed));

        Duration delay = policy.backoff(attempt);

        assertThat(delay).isBetween(Duration.ZERO, RetryPolicy.cap(attempt));
        assertThat(RetryPolicy.cap(attempt)).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @ParameterizedTest
    @CsvSource({"1, 1", "2, 2", "3, 4", "9, 256", "10, 300", "64, 300", "1000, 300"})
    void doublesTheCapFromOneSecondToFiveMinutes(int attempt, long seconds) {
        assertThat(RetryPolicy.cap(attempt)).isEqualTo(Duration.ofSeconds(seconds));
    }

    @Test
    void waitsForARetryAfterLongerThanTheBackoff() {
        var policy = new RetryPolicy(new SplittableRandom(1));
        Instant failedAt = CREATED.plusSeconds(1);

        assertThat(policy.nextAttempt(CREATED, failedAt, 1, "120")).contains(failedAt.plusSeconds(120));
    }

    @Test
    void readsRetryAfterAsAnHttpDate() {
        Instant now = Instant.parse("2026-11-21T14:30:00Z");

        assertThat(RetryPolicy.retryAfter("Sat, 21 Nov 2026 14:32:00 GMT", now)).isEqualTo(Duration.ofMinutes(2));
    }

    @ParameterizedTest
    @CsvSource({"soon", "-5", "'Sat, 21 Nov 2026 14:29:00 GMT'"})
    void ignoresARetryAfterThatIsUnreadableOrPast(String value) {
        assertThat(RetryPolicy.retryAfter(value, Instant.parse("2026-11-21T14:30:00Z")))
                .isZero();
    }

    @Test
    void neverSchedulesPastTheWindow() {
        var policy = new RetryPolicy(new SplittableRandom(1));
        Instant failedAt = CREATED.plus(Duration.ofHours(23).plusMinutes(59));

        assertThat(policy.nextAttempt(CREATED, failedAt, 2, "86400")).contains(CREATED.plus(RetryPolicy.WINDOW));
    }

    @Test
    void stopsOnceTheWindowHasPassed() {
        var policy = new RetryPolicy(new SplittableRandom(1));

        assertThat(policy.nextAttempt(CREATED, CREATED.plus(RetryPolicy.WINDOW), 500, null))
                .isEmpty();
    }
}
