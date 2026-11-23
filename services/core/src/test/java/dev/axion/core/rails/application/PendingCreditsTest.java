package dev.axion.core.rails.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The age the inbox's gauge reports for its oldest pending credit. */
class PendingCreditsTest {

    private static final Instant NOW = Instant.parse("2026-11-22T14:30:00Z");

    @Test
    void isTheTimeSinceTheOldestPendingCreditWasReceived() {
        assertThat(PendingCredits.ageInSeconds(Optional.of(NOW.minusMillis(90_500)), NOW))
                .isEqualTo(90.5);
    }

    @Test
    void isZeroWhenNoCreditIsPending() {
        assertThat(PendingCredits.ageInSeconds(Optional.empty(), NOW)).isZero();
    }

    @Test
    void isNeverNegativeForACreditAnotherInstanceReceivedAheadOfThisClock() {
        assertThat(PendingCredits.ageInSeconds(Optional.of(NOW.plusSeconds(2)), NOW))
                .isZero();
    }
}
