package dev.axion.core.webhooks.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.random.RandomGenerator;

/**
 * What follows an attempt to deliver (events and webhooks design, section 8.5): nothing after a
 * success; after a failure, another attempt, later with each one, for {@value #ATTEMPTS} in all,
 * about 68 hours. A {@code 410 Gone} ends the delivery at once: the receiver said the URL is not
 * coming back.
 *
 * <p>Each delay varies by up to {@code 10%} at random, so the deliveries of one outage do not all
 * come back in the same second.
 */
public final class RetryPolicy {

    /** The attempts a delivery gets, its first included. */
    public static final int ATTEMPTS = 9;

    static final double JITTER = 0.1;

    private final RandomGenerator random;

    /**
     * A policy whose jitter comes from the generator, which every sender shares: it must be safe
     * across threads.
     */
    public RetryPolicy(RandomGenerator random) {
        this.random = random;
    }

    /** What comes after a delivery's attempt. */
    public sealed interface Next {

        /** The receiver took the event. */
        record Succeeded() implements Next {}

        /** Attempted again at the time. */
        record RetryAt(Instant at) implements Next {}

        /** No attempt follows: the last one failed, or the receiver answered {@code 410}. */
        record Dead() implements Next {}
    }

    /**
     * What comes after the attempt of the number.
     *
     * @param number the attempt's number, the first being 1
     * @param endedAt when the attempt ended, which the next one's delay counts from
     */
    public Next after(int number, Attempt attempt, Instant endedAt) {
        if (number < 1 || number > ATTEMPTS) {
            throw new IllegalArgumentException("A delivery has attempts 1 to " + ATTEMPTS + ": " + number);
        }
        if (attempt.succeeded()) {
            return new Next.Succeeded();
        }
        if (number == ATTEMPTS || (attempt instanceof Attempt.Answered(int status) && status == 410)) {
            return new Next.Dead();
        }
        double factor = 1 + JITTER * (2 * random.nextDouble() - 1);
        long nanos = Math.round(delayAfter(number).toNanos() * factor);
        // PostgreSQL keeps microseconds; the stored time must be the one computed.
        return new Next.RetryAt(endedAt.plusNanos(nanos).truncatedTo(ChronoUnit.MICROS));
    }

    /** The delay after the failed attempt of the number, before its jitter. */
    static Duration delayAfter(int number) {
        return switch (number) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(30);
            case 4 -> Duration.ofHours(2);
            case 5 -> Duration.ofHours(6);
            case 6 -> Duration.ofHours(12);
            default -> Duration.ofHours(24);
        };
    }
}
