package dev.finaxis.core.shared;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * Generates version 7 UUIDs (<a href="https://www.rfc-editor.org/rfc/rfc9562#section-5.7">RFC 9562,
 * section 5.7</a>), the primary keys of every table.
 *
 * <p>A UUIDv7 starts with the creation time in milliseconds, so new keys are close to each other in
 * a B-tree index instead of scattered over it like random (version 4) UUIDs, and sorting by key
 * sorts by creation time to the millisecond. The remaining 74 bits are random, so keys can be created
 * anywhere without coordination and cannot be guessed from one another. Keys created within the same
 * millisecond are unique but not ordered among themselves.
 *
 * <p>The layout of the 128 bits, most significant first:
 *
 * <pre>
 * 48 bits  Unix time in milliseconds
 *  4 bits  version, 0111 (7)
 * 12 bits  random
 *  2 bits  variant, 10 (RFC 9562)
 * 62 bits  random
 * </pre>
 *
 * <p>The time comes from the injected {@link Clock}, never from the system clock, so tests can fix
 * it. A UUID is an identifier, not a secret: knowing one never grants access to anything.
 */
public final class UuidV7Generator {

    private static final long VERSION_7 = 0x7000L;
    private static final long VARIANT_RFC_9562 = 0x8000_0000_0000_0000L;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;

    private final Clock clock;
    private final RandomGenerator random;

    /** A generator with a cryptographically strong source of randomness. */
    public UuidV7Generator(Clock clock) {
        this(clock, new SecureRandom());
    }

    /** A generator with the given randomness; tests use it to make the random bits predictable. */
    UuidV7Generator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * A new UUIDv7 for the clock's current instant.
     *
     * @throws IllegalStateException if the clock is before 1970 or after the year 10889, which 48
     *     bits of milliseconds cannot hold
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
