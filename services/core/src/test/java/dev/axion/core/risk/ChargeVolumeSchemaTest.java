package dev.axion.core.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

    private static final String RLS_VIOLATION = "violates row-level security policy";

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

    @Test
    void showsTheModuleRoleNoWindowWithNoTenantBound() {
        insert(tenant, "DAILY_AMOUNT", HOUR, 0, 0);
        DatabaseRole.RISK.actAs(jdbc);

        assertThat(visibleWindows()).isZero();
    }

    @Test
    void hidesAnotherTenantsWindowFromTheModuleRole() {
        insert(UUID.randomUUID(), "DAILY_AMOUNT", HOUR, 0, 0);
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleWindows()).isZero();
    }

    @Test
    void addsNothingToAnotherTenantsWindowAsTheModuleRole() {
        var other = UUID.randomUUID();
        insert(other, "HOURLY_COUNT", HOUR, 0, 0);
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        assertThat(jdbc.sql("UPDATE risk.charge_volume SET count = count + 1 WHERE tenant_id = ?")
                        .param(other)
                        .update())
                .isZero();
    }

    @Test
    void deniesTheModuleRoleCreatingAWindowForAnotherTenant() {
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "DAILY_AMOUNT", HOUR, 0, 0))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleCreatingAWindowWithNoTenantBound() {
        DatabaseRole.RISK.actAs(jdbc);

        assertThatThrownBy(() -> insert(tenant, "DAILY_AMOUNT", HOUR, 0, 0))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleMovingAWindowToAnotherTenant() {
        insert(tenant, "DAILY_AMOUNT", HOUR, 0, 0);
        DatabaseRole.RISK.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE risk.charge_volume SET tenant_id = ? WHERE tenant_id = ?")
                        .params(UUID.randomUUID(), tenant)
                        .update())
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void showsTheJobsRoleOldWindowsOnly() {
        var other = UUID.randomUUID();
        insert(tenant, "HOURLY_COUNT", daysAgo(6), 0, 0);
        insert(other, "HOURLY_COUNT", daysAgo(8), 0, 0);
        DatabaseRole.RISK_JOBS.actAs(jdbc);

        assertThat(jdbc.sql("SELECT tenant_id FROM risk.charge_volume WHERE tenant_id IN (?, ?)")
                        .params(tenant, other)
                        .query(UUID.class)
                        .list())
                .containsExactly(other);
    }

    @Test
    void hidesRecentWindowsFromTheJobsRoleEvenWithATenantBound() {
        insert(tenant, "DAILY_AMOUNT", daysAgo(1), 0, 0);
        DatabaseRole.RISK_JOBS.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleWindows()).isZero();
        assertThat(deleteTheTenantsWindows()).isZero();
    }

    @Test
    void letsTheJobsRoleDeleteOldWindowsOnly() {
        insert(tenant, "DAILY_AMOUNT", daysAgo(6), 0, 0);
        insert(tenant, "DAILY_AMOUNT", daysAgo(8), 0, 0);
        DatabaseRole.RISK_JOBS.actAs(jdbc);

        assertThat(deleteTheTenantsWindows()).isOne();
    }

    @Test
    void deniesTheJobsRoleCreatingAWindow() {
        DatabaseRole.RISK_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> insert(tenant, "DAILY_AMOUNT", daysAgo(8), 0, 0))
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheJobsRoleChangingAWindow() {
        insert(tenant, "DAILY_AMOUNT", daysAgo(8), 0, 0);
        DatabaseRole.RISK_JOBS.actAs(jdbc);

        assertThatThrownBy(() ->
                        jdbc.sql("UPDATE risk.charge_volume SET count = 0").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.HOURS);
    }

    private int deleteTheTenantsWindows() {
        return jdbc.sql("DELETE FROM risk.charge_volume WHERE tenant_id = ?")
                .param(tenant)
                .update();
    }

    private long visibleWindows() {
        return jdbc.sql("SELECT count(*) FROM risk.charge_volume")
                .query(Long.class)
                .single();
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
