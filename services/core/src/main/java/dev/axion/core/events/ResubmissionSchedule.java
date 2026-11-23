package dev.axion.core.events;

import java.time.Duration;
import java.time.Instant;
import org.springframework.modulith.events.EventPublication;

/**
 * When a failed publication is handed to its listener again (events and webhooks design, section
 * 4.5): 1, 5 and 30 minutes after the first three attempts, then every 2 hours, for 10 attempts in
 * all, about 13 hours. After the last, the publication is dead and waits for an operator.
 */
final class ResubmissionSchedule {

    /** The attempts a publication gets, its first handling included. */
    static final int ATTEMPTS = 10;

    private ResubmissionSchedule() {}

    /** How long after its last attempt began a publication waits for the next. */
    static Duration delayAfter(int attempts) {
        return switch (attempts) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(30);
            default -> Duration.ofHours(2);
        };
    }

    static boolean isDead(EventPublication publication) {
        return publication.getCompletionAttempts() >= ATTEMPTS;
    }

    /** Whether a failed publication's next attempt is due. */
    static boolean isDue(EventPublication publication, Instant now) {
        if (isDead(publication)) {
            return false;
        }
        Instant next = publication.getLastPublicationDate().plus(delayAfter(publication.getCompletionAttempts()));
        return !now.isBefore(next);
    }
}
