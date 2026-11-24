package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EndpointHealthTest {

    private static final Instant FIRST = Instant.parse("2026-11-24T10:00:00Z");

    private static final Attempt FAILED = new Attempt.Failed(AttemptFailure.TIMEOUT);

    @Test
    void startsFailingAtItsFirstFailedAttempt() {
        assertThat(EndpointHealth.HEALTHY.after(FAILED, FIRST)).isEqualTo(new EndpointHealth(Optional.of(FIRST), 1));
    }

    @Test
    void countsLaterFailuresFromTheFirst() {
        EndpointHealth failing = EndpointHealth.HEALTHY.after(FAILED, FIRST);

        assertThat(failing.after(new Attempt.Answered(500), FIRST.plusSeconds(60)))
                .isEqualTo(new EndpointHealth(Optional.of(FIRST), 2));
    }

    @Test
    void isHealthyAgainAfterASuccess() {
        EndpointHealth failing = new EndpointHealth(Optional.of(FIRST), 7);

        assertThat(failing.after(new Attempt.Answered(204), FIRST.plusSeconds(60)))
                .isEqualTo(EndpointHealth.HEALTHY);
    }

    @Test
    void warrantsDisablingAfterThreeDaysAnd20FailedAttempts() {
        var health = new EndpointHealth(Optional.of(FIRST), 20);

        assertThat(health.warrantsDisabling(FIRST.plus(EndpointHealth.FAILING_FOR)))
                .isTrue();
        assertThat(health.warrantsDisabling(FIRST.plus(Duration.ofDays(5)))).isTrue();
    }

    @Test
    void doesNotWarrantDisablingBeforeThreeDays() {
        var health = new EndpointHealth(Optional.of(FIRST), 5_000);

        assertThat(health.warrantsDisabling(
                        FIRST.plus(EndpointHealth.FAILING_FOR).minusNanos(1000)))
                .isFalse();
    }

    @Test
    void doesNotWarrantDisablingBefore20FailedAttempts() {
        var health = new EndpointHealth(Optional.of(FIRST), 19);

        assertThat(health.warrantsDisabling(FIRST.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void neverWarrantsDisablingAHealthyEndpoint() {
        assertThat(EndpointHealth.HEALTHY.warrantsDisabling(FIRST)).isFalse();
    }

    @Test
    void refusesAFailingTimeWithoutFailuresAndFailuresWithoutOne() {
        assertThatThrownBy(() -> new EndpointHealth(Optional.of(FIRST), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointHealth(Optional.empty(), 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndpointHealth(Optional.of(FIRST), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
