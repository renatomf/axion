package dev.finaxis.core.access.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.MutableClock;
import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
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
        assertThat(limits.tryAcquire(key)).contains(Duration.ofMillis(20));
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
        assertThat(limits.tryAcquire(key)).contains(Duration.ofMillis(40));
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
        assertThat(limits.tryAcquire(third)).contains(Duration.ofMillis(10));
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

        assertThat(limits.tryAcquire(caller(Environment.TEST, new TenantId(UUID.randomUUID()))))
                .isEmpty();
    }

    @Test
    void letsAMemberSpendTheirBurstThenRefusesThem() {
        String member = UUID.randomUUID().toString();

        assertThat(acquired(member, 50)).isEqualTo(40);
        // 20 a second: the next token comes in 50 milliseconds.
        assertThat(limits.tryAcquire(member)).contains(Duration.ofMillis(50));
    }

    @Test
    void keepsMembersApart() {
        acquired(UUID.randomUUID().toString(), 40);

        assertThat(limits.tryAcquire(UUID.randomUUID().toString())).isEmpty();
    }

    private int acquired(String member, int attempts) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            if (limits.tryAcquire(member).isEmpty()) {
                acquired++;
            }
        }
        return acquired;
    }

    private int acquired(ApiKeyCaller caller, int attempts) {
        int acquired = 0;
        for (int i = 0; i < attempts; i++) {
            if (limits.tryAcquire(caller).isEmpty()) {
                acquired++;
            }
        }
        return acquired;
    }

    private static ApiKeyCaller caller(Environment environment, TenantId tenant) {
        return new ApiKeyCaller(tenant, environment, new ApiKeyId(UUID.randomUUID()), Set.of(Permission.CHARGES_READ));
    }
}
