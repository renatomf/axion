package dev.axion.core.webhooks.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * How an endpoint has fared since its last success (events and webhooks design, section 8.6), and
 * whether it has failed long enough to be disabled: for three days, with its last {@value
 * #FAILED_ATTEMPTS} attempts all failed. The two together keep an endpoint that receives one event a
 * week from being disabled by a single bad night, and one that receives thousands from being
 * disabled in minutes.
 *
 * @param failingSince when its first attempt failed since then; empty while it succeeds
 * @param consecutiveFailures how many attempts have failed since then
 */
public record EndpointHealth(Optional<Instant> failingSince, int consecutiveFailures) {

    /** How long an endpoint fails before it can be disabled. */
    public static final Duration FAILING_FOR = Duration.ofDays(3);

    /** How many attempts in a row fail before it can be disabled. */
    public static final int FAILED_ATTEMPTS = 20;

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

    /** Whether the endpoint has failed long enough, by the time, to be disabled. */
    public boolean warrantsDisabling(Instant now) {
        return consecutiveFailures >= FAILED_ATTEMPTS
                && failingSince
                        .filter(since -> !since.plus(FAILING_FOR).isAfter(now))
                        .isPresent();
    }
}
