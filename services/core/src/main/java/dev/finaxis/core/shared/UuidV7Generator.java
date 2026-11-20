package dev.finaxis.core.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Version 7 UUIDs (RFC 9562, section 5.7), the primary keys of every table: time-ordered, so new
 * keys stay together in a B-tree index, and 74 random bits, so they cannot be guessed. Within one
 * millisecond they are not ordered.
 */
public final class UuidV7Generator {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;

    private final Clock clock;
    private final RandomGenerator random;

    public UuidV7Generator(Clock clock) {
        this(clock, new SecureRandom());
    }

    UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * A UUIDv7 for the clock's instant.
     *
     * @throws IllegalStateException if the clock is outside what 48 bits of milliseconds can hold
     */
    public UUID next() {
        long timestamp = clock.millis();
        if (timestamp < 0 || timestamp > MAX_TIMESTAMP) {
            throw new IllegalStateException("Clock time " + timestamp + " ms does not fit a UUIDv7");
        }
        long mostSignificant = (timestamp << 16) | VERSION_7 | (random.nextLong() & 0x0FFFL);
        long leastSignificant = (random.nextLong() >>> 2) | VARIANT_RFC_9562;
        return new UUID(mostSignificant, leastSignificant);
    }
}
