package dev.finaxis.core.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code billing.find_charge_tenant}, which resolves a credit's transaction ID to its charge's
 * tenant before any tenant is bound (billing design, section 7.3). Nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class TransactionIdLookupTest {

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;

    @Autowired
    TransactionIdLookupTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void findsTheTenantOfTheChargeWithNoTenantBound() {
        var tenant = UUID.randomUUID();
        String transactionId = insertCharge(tenant);
        DatabaseRole.BILLING.actAs(jdbc);

        assertThat(tenantOf(transactionId)).contains(tenant);
    }

    @Test
    void findsTheTenantOfAChargeThatRowLevelSecurityHidesFromTheBoundTenant() {
        var tenant = UUID.randomUUID();
        String transactionId = insertCharge(tenant);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(UUID.randomUUID()));

        assertThat(tenantOf(transactionId)).contains(tenant);
    }

    @Test
    void findsNoTenantForATransactionIdNoChargeHas() {
        insertCharge(UUID.randomUUID());
        DatabaseRole.BILLING.actAs(jdbc);

        assertThat(tenantOf("fx7Kq2mP9wR4tY6uA8sD3fG5h")).isEmpty();
    }

    @Test
    void runsAsTheLookupRoleAndOnlyForTheBillingRole() {
        var lookup = jdbc.sql("""
                        SELECT p.prosecdef, pg_get_userbyid(p.proowner) AS owner,
                               has_function_privilege('finaxis_billing', p.oid, 'EXECUTE') AS billing,
                               has_function_privilege('finaxis_rails', p.oid, 'EXECUTE') AS rails
                          FROM pg_proc p
                         WHERE p.oid = CAST('billing.find_charge_tenant(text)' AS regprocedure)
                        """)
                .query((row, rowNumber) -> List.of(
                        row.getBoolean("prosecdef"),
                        row.getString("owner"),
                        row.getBoolean("billing"),
                        row.getBoolean("rails")))
                .single();

        assertThat(lookup).containsExactly(true, "finaxis_billing_lookup", true, false);
    }

    @Test
    void letsTheLookupRoleReadNoColumnButTheTenantAndTheTransactionId() {
        insertCharge(UUID.randomUUID());
        jdbc.sql("SET LOCAL ROLE finaxis_billing_lookup").update();

        assertThatThrownBy(() -> jdbc.sql("SELECT br_code FROM billing.charges")
                        .query(String.class)
                        .list())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    // The function answers null for an unknown ID; the filter turns that into no row.
    private Optional<UUID> tenantOf(String transactionId) {
        return jdbc.sql("SELECT tenant FROM billing.find_charge_tenant(?) AS tenant WHERE tenant IS NOT NULL")
                .param(transactionId)
                .query(UUID.class)
                .optional();
    }

    private String insertCharge(UUID tenant) {
        var id = UUID.randomUUID();
        String transactionId = id.toString().replace("-", "").substring(0, 25);
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, metadata,
                                transaction_id, br_code, expires_at, created_at, updated_at)
                        VALUES (:id, :tenant, 'ACTIVE', 10000, 'BRL', 0, '{}', :transactionId, '000201',
                                :expiresAt, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("tenant", tenant)
                .param("transactionId", transactionId)
                .param("expiresAt", createdAt.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        return transactionId;
    }
}
