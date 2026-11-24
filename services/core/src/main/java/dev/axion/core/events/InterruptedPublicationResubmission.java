package dev.axion.core.events;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.stereotype.Service;

/**
 * Hands the publications a stopped process left incomplete back to their listeners when the core
 * starts (events and webhooks design, section 4.5), so an event committed just before a restart is
 * handled within seconds of the next start. Dead publications stay dead.
 *
 * <p>Modulith resubmits failed publications only, so those the stopped process left waiting,
 * running or resubmitted are first marked failed, as its staleness monitor would mark them minutes
 * later.
 */
@Service
class InterruptedPublicationResubmission {

    // A staleness of zero means none to Modulith; the shortest one marks what came before the start.
    private static final Duration SHORTEST = Duration.ofNanos(1);

    private final EventPublicationRegistry registry;
    private final FailedEventPublications failed;
    private final Clock clock;

    InterruptedPublicationResubmission(EventPublicationRegistry registry, FailedEventPublications failed, Clock clock) {
        this.registry = registry;
        this.failed = failed;
        this.clock = clock;
    }

    /**
     * @param startedAt when this process started: what it published since is its own, still running
     */
    void resubmitPublishedBefore(Instant startedAt) {
        Duration sinceStart = Duration.between(startedAt, clock.instant());
        Duration staleness = sinceStart.compareTo(SHORTEST) < 0 ? SHORTEST : sinceStart;
        registry.markStalePublicationsFailed(status -> staleness);
        failed.resubmit(ResubmissionOptions.defaults()
                .withBatchSize(Integer.MAX_VALUE)
                .withFilter(publication -> publication.getPublicationDate().isBefore(startedAt)
                        && !ResubmissionSchedule.isDead(publication)));
    }
}
