package dev.axion.core.access.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.axion.core.access.ApiKeyCaller;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

    /**
     * Refilled at {@code perSecond} tokens a second, holding at most {@code burst}: a quota of
     * {@code burst} requests over a window of {@code burst / perSecond} whole seconds.
     */
    record Limit(long perSecond, long burst) {

        Limit {
            if (perSecond <= 0 || burst % perSecond != 0) {
                throw new IllegalArgumentException("A burst must be a whole number of seconds of refill");
            }
        }

        long windowSeconds() {
            return burst / perSecond;
        }
    }

    /** A bucket's state once a request has been counted, under the name of its policy. */
    record Quota(String policy, Limit limit, long remaining) {

        /** Whole seconds until the bucket is full again, rounded up. */
        long resetSeconds() {
            long missing = limit.burst() - remaining;
            return (missing + limit.perSecond() - 1) / limit.perSecond();
        }
    }

    /** The request's quotas, and how long until a retry can succeed if it was refused. */
    record Decision(List<Quota> quotas, Optional<Duration> retryAfter) {

        Decision {
            quotas = List.copyOf(quotas);
        }
    }

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

    /** Takes the request's tokens from its key's and its tenant's buckets, or from neither. */
    Decision tryAcquire(ApiKeyCaller caller) {
        Limit keyLimit = perKey(caller.environment());
        Limit tenantLimit = perTenant(caller.environment());
        Bucket key = keys.get(caller.key(), _ -> bucket(keyLimit));
        Bucket tenant = tenants.get(caller.tenant(), _ -> bucket(tenantLimit));
        Optional<Duration> retryAfter = refusal(key.tryConsumeAndReturnRemaining(1));
        if (retryAfter.isEmpty()) {
            retryAfter = refusal(tenant.tryConsumeAndReturnRemaining(1));
            if (retryAfter.isPresent()) {
                // The request is refused, so it must not spend its key's token either.
                key.addTokens(1);
            }
        }
        return new Decision(List.of(quota("key", keyLimit, key), quota("tenant", tenantLimit, tenant)), retryAfter);
    }

    /** As {@link #tryAcquire(ApiKeyCaller)}, for the member whose access token has this subject. */
    Decision tryAcquire(String memberSubject) {
        Bucket member = members.get(memberSubject, _ -> bucket(PER_MEMBER));
        Optional<Duration> retryAfter = refusal(member.tryConsumeAndReturnRemaining(1));
        return new Decision(List.of(quota("member", PER_MEMBER, member)), retryAfter);
    }

    private static Optional<Duration> refusal(ConsumptionProbe probe) {
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    private static Quota quota(String policy, Limit limit, Bucket bucket) {
        return new Quota(policy, limit, bucket.getAvailableTokens());
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
