package dev.axion.core.events;

import java.time.Instant;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.stereotype.Service;

/**
 * Hands the publications a stopped process left incomplete back to their listeners when the core
 * starts (events and webhooks design, section 4.5), so an event committed just before a restart is
 * handled within seconds of the next start. Dead publications stay dead.
 */
@Service
class InterruptedPublicationResubmission {

    private final IncompleteEventPublications incomplete;

    InterruptedPublicationResubmission(IncompleteEventPublications incomplete) {
        this.incomplete = incomplete;
    }

    /**
     * @param startedAt when this process started: what it published since is its own, still running
     */
    void resubmitPublishedBefore(Instant startedAt) {
        incomplete.resubmitIncompletePublications(publication ->
                publication.getPublicationDate().isBefore(startedAt) && !ResubmissionSchedule.isDead(publication));
    }
}
