package dev.finaxis.simulator.delivery;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.jspecify.annotations.Nullable;

/**
 * When a delivery the core did not accept is tried again (rails simulator design, section 6.2): after a
 * delay drawn uniformly between zero and {@code min(5 minutes, 1 second × 2^(attempt − 1))}, the "full
 * jitter" of exponential backoff, or after the core's {@code Retry-After} if it is longer; never once 24
 * hours have passed since the delivery was created.
 */
final class RetryPolicy {

    static final Duration FIRST_CAP = Duration.ofSeconds(1);
    static final Duration MAX_CAP = Duration.ofMinutes(5);
    static final Duration WINDOW = Duration.ofHours(24);

    /** 2^9 seconds already exceeds 5 minutes; a larger exponent would only overflow. */
    private static final int MAX_DOUBLINGS = 9;

    private final RandomGenerator random;

    RetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    /**
     * The next attempt after a failed one, or empty when the window has passed and the delivery has failed.
     *
     * @param attempt the number of the attempt that failed, from 1
     */
    Optional<Instant> nextAttempt(Instant createdAt, Instant failedAt, int attempt, @Nullable String retryAfter) {
        Instant deadline = createdAt.plus(WINDOW);
        if (!failedAt.isBefore(deadline)) {
            return Optional.empty();
        }
        Duration wait = backoff(attempt);
        Duration asked = retryAfter == null ? Duration.ZERO : retryAfter(retryAfter, failedAt);
        Instant next = failedAt.plus(asked.compareTo(wait) > 0 ? asked : wait);
        return Optional.of(next.isAfter(deadline) ? deadline : next);
    }

    /** A delay drawn uniformly between zero and the cap of this attempt, both included, to the millisecond. */
    Duration backoff(int attempt) {
        return Duration.ofMillis(random.nextLong(cap(attempt).toMillis() + 1));
    }

    static Duration cap(int attempt) {
        if (attempt - 1 >= MAX_DOUBLINGS) {
            return MAX_CAP;
        }
        Duration cap = FIRST_CAP.multipliedBy(1L << (attempt - 1));
        return cap.compareTo(MAX_CAP) > 0 ? MAX_CAP : cap;
    }

    /** {@code Retry-After} as seconds or as an HTTP date (RFC 9110, section 10.2.3); zero when unreadable or past. */
    static Duration retryAfter(String value, Instant now) {
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]{1,10}")) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }
        try {
            Duration until = Duration.between(
                    now,
                    ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant());
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException unreadable) {
            return Duration.ZERO;
        }
    }
}
