package dev.finaxis.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The risk counters table and the rules the database enforces on it (billing design, section 11),
 * checked against a real PostgreSQL. The owner arranges the rows, then the module's role reads and
 * writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ChargeVolumeSchemaTest {

    private static final Instant HOUR = Instant.parse("2026-11-20T15:00:00Z");

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    ChargeVolumeSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAWindowOfEachRule() {
        insert(tenant, "DAILY_AMOUNT", HOUR, 10_000, 1);
        insert(tenant, "HOURLY_COUNT", HOUR, 10_000, 1);

        assertThat(jdbc.sql("SELECT count(*) FROM risk.charge_volume WHERE tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    // AMOUNT compares one charge with its limit, so it has no window to count.
    @Test
    void rejectsARuleWithoutAWindow() {
        assertRejected(() -> insert(tenant, "AMOUNT", HOUR, 0, 0), "charge_volume_rule_check");
    }

    @Test
    void rejectsANegativeTotal() {
        assertRejected(() -> insert(tenant, "DAILY_AMOUNT", HOUR, -1, 0), "charge_volume_total_minor_check");
    }

    @Test
    void rejectsANegativeCount() {
        assertRejected(() -> insert(tenant, "HOURLY_COUNT", HOUR, 0, -1), "charge_volume_count_check");
    }

    @Test
    void rejectsASecondRowForTheSameWindow() {
        insert(tenant, "HOURLY_COUNT", HOUR, 0, 0);

        assertRejected(() -> insert(tenant, "HOURLY_COUNT", HOUR, 0, 0), "charge_volume_pkey");
    }

    @Test
    void keepsTheSameWindowOfTwoTenantsAsTwoRows() {
        var other = UUID.randomUUID();
        insert(tenant, "HOURLY_COUNT", HOUR, 0, 0);
        insert(other, "HOURLY_COUNT", HOUR, 0, 0);

        assertThat(jdbc.sql("SELECT count(*) FROM risk.charge_volume WHERE tenant_id IN (?, ?)")
                        .params(tenant, other)
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void letsTheModuleRoleCreateAWindowAndAddAChargeToIt() {
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        insert(tenant, "DAILY_AMOUNT", HOUR, 0, 0);
        jdbc.sql("""
                        UPDATE risk.charge_volume SET total_minor = total_minor + 500, count = count + 1
                         WHERE tenant_id = ? AND rule = 'DAILY_AMOUNT'
                        """).param(tenant).update();

        assertThat(jdbc.sql("SELECT total_minor FROM risk.charge_volume WHERE tenant_id = ?")
                        .param(tenant)
                        .query(Long.class)
                        .single())
                .isEqualTo(500);
    }

    @Test
    void deniesTheModuleRoleDeletingAWindow() {
        insert(tenant, "DAILY_AMOUNT", HOUR, 0, 0);
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM risk.charge_volume WHERE tenant_id = ?")
                        .param(tenant)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void insert(UUID tenantId, String rule, Instant windowStart, long total, int count) {
        jdbc.sql("""
                        INSERT INTO risk.charge_volume (tenant_id, rule, window_start, total_minor, count)
                        VALUES (?, ?, ?, ?, ?)
                        """)
                .params(tenantId, rule, windowStart.atOffset(ZoneOffset.UTC), total, count)
                .update();
    }
}
