package dev.axion.core.events;

import java.time.Clock;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.stereotype.Service;

/**
 * Hands the failed publications whose next attempt is due back to their listeners (events and
 * webhooks design, section 4.5). Each listener has its own publication of an event, so one that
 * keeps failing delays no other.
 */
@Service
class FailedPublicationResubmission {

    private final FailedEventPublications failed;
    private final Clock clock;

    FailedPublicationResubmission(FailedEventPublications failed, Clock clock) {
        this.failed = failed;
        this.clock = clock;
    }

    // Every failed publication is read, not a batch of the oldest: dead ones never leave the table,
    // and a batch of them would starve the publications still due.
    void resubmitDue() {
        var now = clock.instant();
        failed.resubmit(ResubmissionOptions.defaults()
                .withBatchSize(Integer.MAX_VALUE)
                .withFilter(publication -> ResubmissionSchedule.isDue(publication, now)));
    }
}
