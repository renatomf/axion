package dev.finaxis.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.MutableClock;
import dev.finaxis.core.PostgresTestConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * {@link ChargeExpiry} against a real PostgreSQL. The owner commits the charges, since the job
 * expires them in transactions of its own; the clock stands at {@link #NOW}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, ChargeExpiryTest.Configuration.class})
class ChargeExpiryTest {

    private static final Instant NOW = Instant.parse("2026-11-22T14:30:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(NOW);
        }
    }

    private final ChargeExpiry expiry;
    private final DatabaseOwner owner;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    ChargeExpiryTest(ChargeExpiry expiry, DatabaseOwner owner) {
        this.expiry = expiry;
        this.owner = owner;
    }

    @Test
    void expiresDueChargesInAsManyBatchesAsTheyTake() {
        int due = 2 * ChargeExpiry.BATCH_SIZE + 200;
        insertCharges(due, "ACTIVE", NOW.minus(Duration.ofMinutes(5)));

        assertThat(expiry.expireDue()).isGreaterThanOrEqualTo(due);
        assertThat(chargesOfTheTenant("EXPIRED")).isEqualTo(due);
    }

    @Test
    void expiresAChargeAtItsExpiry() {
        insertCharges(1, "ACTIVE", NOW);

        expiry.expireDue();

        assertThat(chargesOfTheTenant("EXPIRED")).isOne();
    }

    @Test
    void leavesAChargeActiveUntilItsExpiry() {
        insertCharges(1, "ACTIVE", NOW.plusNanos(1_000));

        expiry.expireDue();

        assertThat(chargesOfTheTenant("ACTIVE")).isOne();
    }

    @Test
    void leavesAPaidChargePaid() {
        insertCharges(1, "PAID", NOW.minus(Duration.ofMinutes(5)));

        expiry.expireDue();

        assertThat(chargesOfTheTenant("PAID")).isOne();
    }

    @Test
    void skipsAChargeASettlementHoldsLockedAndExpiresItOnTheNextPass() {
        insertCharges(2, "ACTIVE", NOW.minus(Duration.ofMinutes(5)));
        UUID locked = owner.jdbc()
                .sql("SELECT id FROM billing.charges WHERE tenant_id = ? LIMIT 1")
                .param(tenant)
                .query(UUID.class)
                .single();

        try (var settlement = owner.session()) {
            settlement
                    .jdbc()
                    .sql("SELECT id FROM billing.charges WHERE id = ? FOR UPDATE")
                    .param(locked)
                    .query(UUID.class)
                    .single();

            expiry.expireDue();

            assertThat(chargesOfTheTenant("EXPIRED")).isOne();
            assertThat(statusOf(locked)).isEqualTo("ACTIVE");
        }

        expiry.expireDue();

        assertThat(chargesOfTheTenant("EXPIRED")).isEqualTo(2);
    }

    @Test
    void expiresNothingWhenNoChargeIsDue() {
        expiry.expireDue();

        assertThat(expiry.expireDue()).isZero();
    }

    // Charges of the tenant created an hour before their expiry; paid at creation when PAID.
    private void insertCharges(int count, String status, Instant expiresAt) {
        owner.jdbc()
                .sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, metadata,
                                transaction_id, br_code, expires_at, paid_at, created_at, updated_at)
                        SELECT gen_random_uuid(), :tenant, :status, 10000, 'BRL', 0, '{}',
                               substr(replace(gen_random_uuid()::text, '-', ''), 1, 25), '000201', :expiresAt,
                               CASE WHEN :status = 'PAID' THEN :createdAt END, :createdAt, :createdAt
                          FROM generate_series(1, :count)
                        """)
                .param("tenant", tenant)
                .param("status", status)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .param("createdAt", expiresAt.minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .param("count", count)
                .update();
    }

    private long chargesOfTheTenant(String status) {
        return owner.jdbc()
                .sql("SELECT count(*) FROM billing.charges WHERE tenant_id = ? AND status = ?")
                .params(tenant, status)
                .query(Long.class)
                .single();
    }

    private String statusOf(UUID charge) {
        return owner.jdbc()
                .sql("SELECT status FROM billing.charges WHERE id = ?")
                .param(charge)
                .query(String.class)
                .single();
    }
}
