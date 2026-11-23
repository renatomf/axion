package dev.finaxis.core.rails.security;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The request budget of each signing key (billing design, section 7.1): {@value #PER_SECOND}
 * requests a second with bursts of {@value #BURST}, the budget of a live tenant. A key is counted
 * only once its signature verified, so a forger cannot spend the rails' budget.
 *
 * <p>Buckets live in memory, one per configured key, so each core instance enforces its own copy.
 */
final class SigningKeyRateLimits {

    static final long PER_SECOND = 100;
    static final long BURST = 200;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final TimeMeter time;

    SigningKeyRateLimits(Clock clock) {
        this.time = new ClockTimeMeter(clock);
    }

    /** Takes a token from the key's bucket; empty if it had one, otherwise how long until it will. */
    Optional<Duration> tryAcquire(String keyId) {
        ConsumptionProbe probe = buckets.computeIfAbsent(keyId, _ -> bucket()).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private Bucket bucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(BURST).refillGreedy(PER_SECOND, Duration.ofSeconds(1)))
                .withCustomTimePrecision(time)
                .build();
    }

    /** Bucket4j reads time from the injected clock, as the rest of the service does. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return Math.addExact(Math.multiplyExact(now.getEpochSecond(), 1_000_000_000L), now.getNano());
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
