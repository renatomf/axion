package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.modulith.events.EventPublication;

/** The backoff of failed publications (events and webhooks design, section 4.5). */
class ResubmissionScheduleTest {

    private static final Instant PUBLISHED = Instant.parse("2026-11-23T14:00:00Z");

    @ParameterizedTest
    @CsvSource({"1, PT1M", "2, PT5M", "3, PT30M", "4, PT2H", "9, PT2H"})
    void waitsLongerAfterEachOfTheFirstAttempts(int attempts, Duration delay) {
        assertThat(ResubmissionSchedule.delayAfter(attempts)).isEqualTo(delay);
    }

    @Test
    void isDueOnceTheDelayHasPassedSinceTheLastAttemptBegan() {
        var resubmitted = PUBLISHED.plus(Duration.ofMinutes(10));
        var publication = new Publication(3, resubmitted);

        assertThat(ResubmissionSchedule.isDue(publication, resubmitted.plus(Duration.ofMinutes(29))))
                .isFalse();
        assertThat(ResubmissionSchedule.isDue(publication, resubmitted.plus(Duration.ofMinutes(30))))
                .isTrue();
    }

    @Test
    void countsTheFirstAttemptFromThePublication() {
        var publication = new Publication(1, null);

        assertThat(ResubmissionSchedule.isDue(publication, PUBLISHED.plusSeconds(59)))
                .isFalse();
        assertThat(ResubmissionSchedule.isDue(publication, PUBLISHED.plusSeconds(60)))
                .isTrue();
    }

    @Test
    void neverResubmitsAPublicationAfterItsTenthAttempt() {
        var publication = new Publication(10, PUBLISHED);

        assertThat(ResubmissionSchedule.isDead(publication)).isTrue();
        assertThat(ResubmissionSchedule.isDue(publication, PUBLISHED.plus(Duration.ofDays(30))))
                .isFalse();
    }

    @Test
    void takesAboutThirteenHoursToGiveUp() {
        Duration total = Duration.ZERO;
        for (int attempts = 1; attempts < ResubmissionSchedule.ATTEMPTS; attempts++) {
            total = total.plus(ResubmissionSchedule.delayAfter(attempts));
        }

        assertThat(total).isEqualTo(Duration.ofHours(12).plusMinutes(36));
    }

    private record Publication(int attempts, @Nullable Instant resubmitted) implements EventPublication {

        @Override
        public UUID getIdentifier() {
            return UUID.randomUUID();
        }

        @Override
        public Object getEvent() {
            return "event";
        }

        @Override
        public Instant getPublicationDate() {
            return PUBLISHED;
        }

        @Override
        public Optional<Instant> getCompletionDate() {
            return Optional.empty();
        }

        @Override
        public Status getStatus() {
            return Status.FAILED;
        }

        @Override
        public @Nullable Instant getLastResubmissionDate() {
            return resubmitted;
        }

        @Override
        public int getCompletionAttempts() {
            return attempts;
        }
    }
}
