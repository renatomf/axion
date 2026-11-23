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
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The refunds table and the rules the database enforces on it (billing design, section 11),
 * checked against a real PostgreSQL. The owner arranges the rows, then the module's role reads and
 * writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RefundsSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID charge = UUID.randomUUID();
    private final UUID payment = UUID.randomUUID();

    @Autowired
    RefundsSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @BeforeEach
    void insertPaidCharge() {
        insertPaidCharge(charge, payment, tenant);
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesARefundOfAPayment() {
        var row = row();
        insert(row);

        assertThat(jdbc.sql("SELECT status FROM billing.refunds WHERE id = ?")
                        .param(row.id)
                        .query(String.class)
                        .single())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsARefundOfNoCharge() {
        assertRejected(row().charge(UUID.randomUUID()), "refunds_charge_id_fkey");
    }

    @Test
    void rejectsARefundOfNoPayment() {
        assertRejected(row().payment(UUID.randomUUID()), "refunds_payment_id_fkey");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void rejectsAnAmountThatIsNotPositive(long amount) {
        assertRejected(row().amount(amount), "refunds_amount_minor_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED"})
    void rejectsAStatusPhaseOneNeverReaches(String status) {
        assertRejected(row().status(status), "refunds_status_check");
    }

    @Test
    void letsTheModuleRoleInsertAndReadARefund() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));
        insert(row());

        assertThat(visibleRefunds()).isOne();
    }

    @Test
    void deniesTheModuleRoleUpdatingARefund() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE billing.refunds SET amount_minor = 1 WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleDeletingARefund() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing.refunds WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void showsTheModuleRoleNoRefundWithNoTenantBound() {
        insert(row());
        DatabaseRole.BILLING.actAs(jdbc);

        assertThat(visibleRefunds()).isZero();
    }

    @Test
    void hidesAnotherTenantsRefundFromTheModuleRole() {
        var otherTenant = UUID.randomUUID();
        var otherCharge = UUID.randomUUID();
        var otherPayment = UUID.randomUUID();
        insertPaidCharge(otherCharge, otherPayment, otherTenant);
        insert(row().tenant(otherTenant).charge(otherCharge).payment(otherPayment));
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleRefunds()).isZero();
    }

    @Test
    void deniesTheModuleRoleInsertingARefundForAnotherTenant() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(row().tenant(UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingARefundWithNoTenantBound() {
        DatabaseRole.BILLING.actAs(jdbc);

        assertThatThrownBy(() -> insert(row())).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    private long visibleRefunds() {
        return jdbc.sql("SELECT count(*) FROM billing.refunds")
                .query(Long.class)
                .single();
    }

    private void assertRejected(Row row, String constraint) {
        assertThatThrownBy(() -> insert(row))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private Row row() {
        return new Row(tenant, charge, payment);
    }

    private void insertPaidCharge(UUID chargeId, UUID paymentId, UUID owner) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, metadata,
                                transaction_id, br_code, expires_at, paid_at, created_at, updated_at)
                        VALUES (:id, :tenant, 'PAID', 10000, 'BRL', 0, '{}', :transactionId, '000201',
                                :expiresAt, :createdAt, :createdAt, :createdAt)
                        """)
                .param("id", chargeId)
                .param("tenant", owner)
                .param("transactionId", chargeId.toString().replace("-", "").substring(0, 25))
                .param("expiresAt", createdAt.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                        INSERT INTO billing.payments
                               (id, tenant_id, charge_id, end_to_end_id, amount_minor, amount_refunded,
                                accepted_at, outcome, entry_id, created_at)
                        VALUES (:id, :tenant, :charge, :endToEndId, 10000, 0, :acceptedAt, 'SETTLED',
                                :entry, :acceptedAt)
                        """)
                .param("id", paymentId)
                .param("tenant", owner)
                .param("charge", chargeId)
                .param("endToEndId", BillingFixtures.endToEndId())
                .param("acceptedAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("entry", UUID.randomUUID())
                .update();
    }

    private void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO billing.refunds
                               (id, tenant_id, charge_id, payment_id, amount_minor, status, entry_id,
                                correlation_id, created_at)
                        VALUES (:id, :tenant, :charge, :payment, :amount, :status, :entry, :correlationId,
                                :createdAt)
                        """)
                .param("id", row.id)
                .param("tenant", row.tenant)
                .param("charge", row.charge)
                .param("payment", row.payment)
                .param("amount", row.amount)
                .param("status", row.status)
                .param("entry", UUID.randomUUID())
                .param("correlationId", "req-" + row.id)
                .param("createdAt", row.createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A valid partial refund of the payment; each method changes one column. */
    private static final class Row {

        private final UUID id = UUID.randomUUID();
        private UUID tenant;
        private UUID charge;
        private UUID payment;
        private long amount = 2_500;
        private String status = "SUCCEEDED";
        private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        private Row(UUID tenant, UUID charge, UUID payment) {
            this.tenant = tenant;
            this.charge = charge;
            this.payment = payment;
        }

        Row tenant(UUID value) {
            tenant = value;
            return this;
        }

        Row charge(UUID value) {
            charge = value;
            return this;
        }

        Row payment(UUID value) {
            payment = value;
            return this;
        }

        Row amount(long value) {
            amount = value;
            return this;
        }

        Row status(String value) {
            status = value;
            return this;
        }
    }
}
