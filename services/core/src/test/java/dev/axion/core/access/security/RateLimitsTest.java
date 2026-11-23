package dev.axion.core.access.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import dev.axion.core.MutableClock;
import dev.axion.core.access.ApiKeyCaller;
import dev.axion.core.access.ApiKeyId;
import dev.axion.core.access.security.RateLimits.Quota;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RateLimitsTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-20T12:00:00Z"));
    private final RateLimits limits = new RateLimits(clock);

    @Test
    void letsAKeySpendItsBurstThenRefusesIt() {
        ApiKeyCaller key = caller(Environment.LIVE, new TenantId(UUID.randomUUID()));

        assertThat(acquired(key, 100)).isEqualTo(100);
        // 50 a second: the next token comes in 20 milliseconds.
        assertThat(limits.tryAcquire(key).retryAfter()).contains(Duration.ofMillis(20));
    }

    @Test
    void refillsAKeyWithTime() {
        ApiKeyCaller key = caller(Environment.LIVE, new TenantId(UUID.randomUUID()));
        acquired(key, 100);

        clock.advance(Duration.ofMillis(100));

        assertThat(acquired(key, 10)).isEqualTo(5);
    }

    @Test
    void sizesTestBucketsSmaller() {
        ApiKeyCaller key = caller(Environment.TEST, new TenantId(UUID.randomUUID()));

        assertThat(acquired(key, 100)).isEqualTo(50);
        assertThat(limits.tryAcquire(key).retryAfter()).contains(Duration.ofMillis(40));
    }

    @Test
    void sharesTheTenantsBucketAmongItsKeys() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        ApiKeyCaller first = caller(Environment.LIVE, tenant);
        ApiKeyCaller second = caller(Environment.LIVE, tenant);
        ApiKeyCaller third = caller(Environment.LIVE, tenant);

        assertThat(acquired(first, 100)).isEqualTo(100);
        assertThat(acquired(second, 100)).isEqualTo(100);
        // 100 a second for the tenant: the next token comes in 10 milliseconds.
        assertThat(limits.tryAcquire(third).retryAfter()).contains(Duration.ofMillis(10));
    }

    @Test
    void spendsNoKeyTokenOnARequestTheTenantRefuses() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        ApiKeyCaller first = caller(Environment.LIVE, tenant);
        ApiKeyCaller second = caller(Environment.LIVE, tenant);
        ApiKeyCaller third = caller(Environment.LIVE, tenant);
        acquired(first, 100);
        acquired(second, 100);

        assertThat(acquired(third, 50)).isZero();

        // Another tenant's budget, so only the key's own bucket can refuse now.
        ApiKeyCaller elsewhere =
                new ApiKeyCaller(new TenantId(UUID.randomUUID()), Environment.LIVE, third.key(), third.permissions());
        assertThat(acquired(elsewhere, 200)).isEqualTo(100);
    }

    @Test
    void keepsTenantsApart() {
        ApiKeyCaller busy = caller(Environment.TEST, new TenantId(UUID.randomUUID()));
        acquired(busy, 50);

        assertThat(limits.tryAcquire(caller(Environment.TEST, new TenantId(UUID.randomUUID())))
                        .retryAfter())
                .isEmpty();
    }

    @Test
    void letsAMemberSpendTheirBurstThenRefusesThem() {
        String member = UUID.randomUUID().toString();

        assertThat(acquired(member, 50)).isEqualTo(40);
        // 20 a second: the next token comes in 50 milliseconds.
        assertThat(limits.tryAcquire(member).retryAfter()).contains(Duration.ofMillis(50));
    }

    @Test
    void keepsMembersApart() {
        acquired(UUID.randomUUID().toString(), 40);

        assertThat(limits.tryAcquire(UUID.randomUUID().toString()).retryAfter()).isEmpty();
    }

    @Test
    void reportsWhatIsLeftOfTheKeyAndTheTenant() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        acquired(caller(Environment.LIVE, tenant), 30);

        RateLimits.Decision decision = limits.tryAcquire(caller(Environment.LIVE, tenant));

        assertThat(decision.retryAfter()).isEmpty();
        assertThat(decision.quotas())
                .extracting(Quota::policy, Quota::remaining, Quota::resetSeconds)
                .containsExactly(tuple("key", 99L, 1L), tuple("tenant", 169L, 1L));
    }

    @Test
    void reportsAnEmptyBucketWhenItRefuses() {
        String member = UUID.randomUUID().toString();
        acquired(member, 40);

        RateLimits.Decision decision = limits.tryAcquire(member);

        assertThat(decision.quotas())
                .extracting(Quota::policy, Quota::remaining, Quota::resetSeconds)
                .containsExactly(tuple("member", 0L, 2L));
    }

    @Test
    void reportsTheKeyTokenGivenBackWhenTheTenantRefuses() {
        TenantId tenant = new TenantId(UUID.randomUUID());
        acquired(caller(Environment.LIVE, tenant), 100);
        acquired(caller(Environment.LIVE, tenant), 100);

        RateLimits.Decision decision = limits.tryAcquire(caller(Environment.LIVE, tenant));

        assertThat(decision.retryAfter()).isPresent();
        assertThat(decision.quotas())
                .extracting(Quota::policy, Quota::remaining)
                .containsExactly(tuple("key", 100L), tuple("tenant", 0L));
    }

    @Test
    void rejectsABurstThatIsNotWholeSecondsOfRefill() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimits.Limit(20, 30));
    }

    private int acquired(String member, int attempts) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            if (limits.tryAcquire(member).retryAfter().isEmpty()) {
                acquired++;
            }
        }
        return acquired;
    }

    private int acquired(ApiKeyCaller caller, int attempts) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            if (limits.tryAcquire(caller).retryAfter().isEmpty()) {
                acquired++;
            }
        }
        return acquired;
    }

    private static ApiKeyCaller caller(Environment environment, TenantId tenant) {
        return new ApiKeyCaller(tenant, environment, new ApiKeyId(UUID.randomUUID()), Set.of(Permission.CHARGES_READ));
    }
}
