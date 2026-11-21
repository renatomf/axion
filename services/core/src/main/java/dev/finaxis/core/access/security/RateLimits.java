package dev.finaxis.core.access.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The request budget of authenticated callers (tenancy design, section 6). On {@code /v1}, a token
 * bucket per tenant and one per API key, sized by the environment: a request takes one token from
 * each, or from neither when either is empty. On {@code /internal}, a bucket per member, identified
 * by the subject of their access token, so it holds whichever tenant the request names.
 *
 * <p>Buckets live in memory, so each core instance enforces its own copy of a limit. The caches are
 * bounded: a bucket dropped for size or idleness comes back full, which an idle bucket already is.
 */
final class RateLimits {

    /** Refilled at {@code perSecond} tokens a second, holding at most {@code burst}. */
    record Limit(long perSecond, long burst) {}

    static Limit perTenant(Environment environment) {
        return switch (environment) {
            case LIVE -> new Limit(100, 200);
            case TEST -> new Limit(25, 50);
        };
    }

    static Limit perKey(Environment environment) {
        return switch (environment) {
            case LIVE -> new Limit(50, 100);
            case TEST -> new Limit(25, 50);
        };
    }

    static final Limit PER_MEMBER = new Limit(20, 40);

    static final long MAX_BUCKETS = 100_000;
    static final Duration IDLE = Duration.ofMinutes(10);

    private final Cache<TenantId, Bucket> tenants = cache();
    private final Cache<ApiKeyId, Bucket> keys = cache();
    private final Cache<String, Bucket> members = cache();
    private final TimeMeter time;

    RateLimits(Clock clock) {
        this.time = new ClockTimeMeter(clock);
    }

    /** Takes the request's tokens; empty if it may proceed, otherwise how long until a retry can. */
    Optional<Duration> tryAcquire(ApiKeyCaller caller) {
        Bucket key = keys.get(caller.key(), _ -> bucket(perKey(caller.environment())));
        ConsumptionProbe byKey = key.tryConsumeAndReturnRemaining(1);
        if (!byKey.isConsumed()) {
            return Optional.of(Duration.ofNanos(byKey.getNanosToWaitForRefill()));
        }
        Bucket tenant = tenants.get(caller.tenant(), _ -> bucket(perTenant(caller.environment())));
        ConsumptionProbe byTenant = tenant.tryConsumeAndReturnRemaining(1);
        if (!byTenant.isConsumed()) {
            // The request is refused, so it must not spend its key's token either.
            key.addTokens(1);
            return Optional.of(Duration.ofNanos(byTenant.getNanosToWaitForRefill()));
        }
        return Optional.empty();
    }

    /** As {@link #tryAcquire(ApiKeyCaller)}, for the member whose access token has this subject. */
    Optional<Duration> tryAcquire(String memberSubject) {
        Bucket member = members.get(memberSubject, _ -> bucket(PER_MEMBER));
        ConsumptionProbe probe = member.tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            return Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        }
        return Optional.empty();
    }

    private Bucket bucket(Limit limit) {
        return Bucket.builder()
                .addLimit(bandwidth ->
                        bandwidth.capacity(limit.burst()).refillGreedy(limit.perSecond(), Duration.ofSeconds(1)))
                .withCustomTimePrecision(time)
                .build();
    }

    private static <K> Cache<K, Bucket> cache() {
        return Caffeine.newBuilder()
                .maximumSize(MAX_BUCKETS)
                .expireAfterAccess(IDLE)
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
