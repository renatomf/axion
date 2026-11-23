package dev.finaxis.core.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.TenantId;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * The payments table and the rules the database enforces on it (billing design, section 11),
 * checked against a real PostgreSQL. The owner arranges the rows, then the module's role reads and
 * writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentsSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID charge = UUID.randomUUID();

    @Autowired
    PaymentsSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @BeforeEach
    void insertCharge() {
        insertCharge(charge, tenant);
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesASettlingPayment() {
        var row = row();
        insert(row);

        assertThat(jdbc.sql("SELECT outcome FROM billing.payments WHERE id = ?")
                        .param(row.id)
                        .query(String.class)
                        .single())
                .isEqualTo("SETTLED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CHARGE_ALREADY_PAID", "CHARGE_EXPIRED", "AMOUNT_MISMATCH"})
    void storesAFlaggedPaymentWithItsReason(String reason) {
        insert(row().flagged(reason));
    }

    @Test
    void rejectsAPaymentOfNoCharge() {
        assertRejected(row().charge(UUID.randomUUID()), "payments_charge_id_fkey");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "E99990011202611221430aB3dE5gH7j",
                "E9999001120261122143aB3dE5gH7jKx",
                "X99990011202611221430aB3dE5gH7jK",
                "E99990011202611221430aB3dE5gH7j-"
            })
    void rejectsAnEndToEndIdOutsideTheRailsFormat(String endToEndId) {
        assertRejected(row().endToEndId(endToEndId), "payments_end_to_end_id_check");
    }

    @Test
    void rejectsASecondPaymentWithTheSameEndToEndId() {
        var first = row().flagged("AMOUNT_MISMATCH");
        insert(first);
        var otherCharge = UUID.randomUUID();
        insertCharge(otherCharge, tenant);

        assertRejected(row().charge(otherCharge).endToEndId(first.endToEndId), "payments_end_to_end_id_key");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100})
    void rejectsAnAmountThatIsNotPositive(long amount) {
        assertRejected(row().amount(amount), "payments_amount_minor_check");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 10_001})
    void rejectsARefundedAmountOutsideThePayment(long refunded) {
        assertRejected(row().amount(10_000).refunded(refunded), "payments_amount_refunded_check");
    }

    @Test
    void rejectsAnUnknownOutcome() {
        assertRejected(row().outcome("REFUSED"), "payments_outcome_check");
    }

    @Test
    void rejectsAnUnknownReviewReason() {
        assertRejected(row().flagged("LATE"), "payments_review_reason_check");
    }

    @Test
    void rejectsAFlaggedPaymentWithoutItsReason() {
        assertRejected(row().outcome("FLAGGED"), "payments_review_reason_set_check");
    }

    @Test
    void rejectsAReviewReasonOnASettlingPayment() {
        assertRejected(row().flagged("CHARGE_EXPIRED").outcome("SETTLED"), "payments_review_reason_set_check");
    }

    @Test
    void rejectsASecondSettlingPaymentOfACharge() {
        insert(row());

        assertRejected(row(), "payments_settling_idx");
    }

    @Test
    void acceptsFlaggedPaymentsBesideTheSettlingOne() {
        insert(row());
        insert(row().flagged("CHARGE_ALREADY_PAID"));
        insert(row().flagged("CHARGE_ALREADY_PAID"));
    }

    @Test
    void letsTheModuleRoleInsertAndReadAPayment() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));
        insert(row());

        assertThat(visiblePayments()).isOne();
    }

    @Test
    void letsTheModuleRoleAddToWhatRefundsReturned() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        jdbc.sql("UPDATE billing.payments SET amount_refunded = amount_refunded + 2500 WHERE id = ?")
                .param(row.id)
                .update();

        assertThat(jdbc.sql("SELECT amount_refunded FROM billing.payments WHERE id = ?")
                        .param(row.id)
                        .query(Long.class)
                        .single())
                .isEqualTo(2_500L);
    }

    @Test
    void deniesTheModuleRoleChangingAPaymentsOutcome() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE billing.payments SET outcome = 'FLAGGED' WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleDeletingAPayment() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing.payments WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void showsTheModuleRoleNoPaymentWithNoTenantBound() {
        insert(row());
        DatabaseRole.BILLING.actAs(jdbc);

        assertThat(visiblePayments()).isZero();
    }

    @Test
    void hidesAnotherTenantsPaymentFromTheModuleRole() {
        var otherTenant = UUID.randomUUID();
        var otherCharge = UUID.randomUUID();
        insertCharge(otherCharge, otherTenant);
        insert(row().tenant(otherTenant).charge(otherCharge));
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThat(visiblePayments()).isZero();
    }

    @Test
    void deniesTheModuleRoleInsertingAPaymentForAnotherTenant() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(row().tenant(UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingAPaymentWithNoTenantBound() {
        DatabaseRole.BILLING.actAs(jdbc);

        assertThatThrownBy(() -> insert(row())).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    private long visiblePayments() {
        return jdbc.sql("SELECT count(*) FROM billing.payments")
                .query(Long.class)
                .single();
    }

    private void assertRejected(Row row, String constraint) {
        assertThatThrownBy(() -> insert(row))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private Row row() {
        return new Row(tenant, charge);
    }

    private void insertCharge(UUID id, UUID owner) {
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, metadata,
                                transaction_id, br_code, expires_at, created_at, updated_at)
                        VALUES (:id, :tenant, 'ACTIVE', 10000, 'BRL', 0, '{}', :transactionId, '000201',
                                :expiresAt, :createdAt, :createdAt)
                        """)
                .param("id", id)
                .param("tenant", owner)
                .param("transactionId", id.toString().replace("-", "").substring(0, 25))
                .param("expiresAt", createdAt.plus(Duration.ofMinutes(30)).atOffset(ZoneOffset.UTC))
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO billing.payments
                               (id, tenant_id, charge_id, end_to_end_id, amount_minor, amount_refunded,
                                accepted_at, outcome, review_reason, entry_id, created_at)
                        VALUES (:id, :tenant, :charge, :endToEndId, :amount, :refunded, :acceptedAt, :outcome,
                                :reason, :entry, :acceptedAt)
                        """)
                .param("id", row.id)
                .param("tenant", row.tenant)
                .param("charge", row.charge)
                .param("endToEndId", row.endToEndId)
                .param("amount", row.amount)
                .param("refunded", row.refunded)
                .param("acceptedAt", row.acceptedAt.atOffset(ZoneOffset.UTC))
                .param("outcome", row.outcome)
                .param("reason", row.reason, Types.VARCHAR)
                .param("entry", UUID.randomUUID())
                .update();
    }

    /** A valid settling payment of the charge; each method changes one column. */
    private static final class Row {

        private final UUID id = UUID.randomUUID();
        private UUID tenant;
        private UUID charge;
        private String endToEndId = BillingFixtures.endToEndId();
        private long amount = 10_000;
        private long refunded = 0;
        private final Instant acceptedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        private String outcome = "SETTLED";
        private @Nullable String reason;

        private Row(UUID tenant, UUID charge) {
            this.tenant = tenant;
            this.charge = charge;
        }

        Row tenant(UUID value) {
            tenant = value;
            return this;
        }

        Row charge(UUID value) {
            charge = value;
            return this;
        }

        Row endToEndId(String value) {
            endToEndId = value;
            return this;
        }

        Row amount(long value) {
            amount = value;
            return this;
        }

        Row refunded(long value) {
            refunded = value;
            return this;
        }

        Row outcome(String value) {
            outcome = value;
            return this;
        }

        /** Flagged for the reason. */
        Row flagged(String value) {
            outcome = "FLAGGED";
            reason = value;
            return this;
        }
    }
}
