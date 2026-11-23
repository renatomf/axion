package dev.axion.core.events;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resubmits the publications a stopped process interrupted once the core is ready, then the failed
 * ones every minute. Off when {@code axion.events.resubmission.enabled} is false, so tests run both
 * themselves.
 */
@Component
@ConditionalOnBooleanProperty(name = "axion.events.resubmission.enabled", matchIfMissing = true)
class ScheduledResubmission {

    private final FailedPublicationResubmission failed;
    private final InterruptedPublicationResubmission interrupted;
    private final Instant startedAt;

    // Created while the context starts, before any work of this process can publish.
    ScheduledResubmission(
            FailedPublicationResubmission failed, InterruptedPublicationResubmission interrupted, Clock clock) {
        this.failed = failed;
        this.interrupted = interrupted;
        this.startedAt = clock.instant();
    }

    @EventListener(ApplicationReadyEvent.class)
    void resubmitInterrupted() {
        interrupted.resubmitPublishedBefore(startedAt);
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    void resubmitDue() {
        failed.resubmitDue();
    }
}
