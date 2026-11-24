package dev.axion.core.webhooks.domain;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * One request of a delivery as the delivery log records it (events and webhooks design, section
 * 8.7): what came of it, how long it took, and the start of the receiver's answer.
 *
 * @param duration from the start of the request to its result, the connection included
 * @param responseExcerpt at most {@value #MAX_EXCERPT_BYTES} bytes of the response body, as text;
 *     empty for an attempt that got no answer, or an answer with no body
 */
public record SentAttempt(Attempt attempt, Duration duration, Optional<String> responseExcerpt) {

    /** The most bytes of a response body the log keeps. */
    public static final int MAX_EXCERPT_BYTES = 1_024;

    public SentAttempt {
        Objects.requireNonNull(attempt, "attempt");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("An attempt cannot take a negative time: " + duration);
        }
        if (attempt instanceof Attempt.Failed && responseExcerpt.isPresent()) {
            throw new IllegalArgumentException("An attempt that got no answer has no response to keep");
        }
    }
}
