package dev.finaxis.core.rails.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SigningKeyRateLimitsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-21T14:30:00Z"));
    private final SigningKeyRateLimits limits = new SigningKeyRateLimits(clock);

    @Test
    void allowsABurstOfTwoHundred() {
        assertThat(IntStream.range(0, 200).mapToObj(_ -> limits.tryAcquire("rails-2026-11")))
                .allMatch(retryAfter -> retryAfter.isEmpty());
    }

    @Test
    void refusesTheNextRequestUntilATokenRefills() {
        exhaust("rails-2026-11");

        assertThat(limits.tryAcquire("rails-2026-11")).hasValue(Duration.ofMillis(10));
    }

    @Test
    void refillsAHundredASecond() {
        exhaust("rails-2026-11");
        clock.advance(Duration.ofSeconds(1));

        assertThat(IntStream.range(0, 100).mapToObj(_ -> limits.tryAcquire("rails-2026-11")))
                .allMatch(retryAfter -> retryAfter.isEmpty());
        assertThat(limits.tryAcquire("rails-2026-11")).isPresent();
    }

    // During a rotation each key has its own budget.
    @Test
    void countsEachKeyApart() {
        exhaust("rails-2026-10");

        assertThat(limits.tryAcquire("rails-2026-11")).isEmpty();
    }

    private void exhaust(String keyId) {
        IntStream.range(0, 200).forEach(_ -> limits.tryAcquire(keyId));
    }
}
