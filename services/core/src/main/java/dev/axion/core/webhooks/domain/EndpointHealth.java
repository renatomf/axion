package dev.axion.core.webhooks.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * How an endpoint has fared since its last success (events and webhooks design, section 8.6).
 *
 * @param failingSince when its first attempt failed since then; empty while it succeeds
 * @param consecutiveFailures how many attempts have failed since then
 */
public record EndpointHealth(Optional<Instant> failingSince, int consecutiveFailures) {

    /** An endpoint whose last attempt succeeded, or that has had none. */
    public static final EndpointHealth HEALTHY = new EndpointHealth(Optional.empty(), 0);

    public EndpointHealth {
        if (consecutiveFailures < 0 || failingSince.isPresent() != (consecutiveFailures > 0)) {
            throw new IllegalArgumentException("An endpoint is failing since a time exactly when attempts have failed: "
                    + failingSince + ", " + consecutiveFailures);
        }
    }

    /** The endpoint's health once the attempt, which ended at the time, is counted. */
    public EndpointHealth after(Attempt attempt, Instant endedAt) {
        if (attempt.succeeded()) {
            return HEALTHY;
        }
        return new EndpointHealth(failingSince.or(() -> Optional.of(endedAt)), consecutiveFailures + 1);
    }
}
