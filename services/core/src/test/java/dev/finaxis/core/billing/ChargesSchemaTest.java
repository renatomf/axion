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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The charges table and the rules the database enforces on it (billing design, section 11), checked
 * against a real PostgreSQL. The owner arranges the rows, then the module's role reads and writes
 * them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ChargesSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    ChargesSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAnActiveCharge() {
        var row = row();
        insert(row);

        assertThat(jdbc.sql("SELECT status FROM billing.charges WHERE id = ?")
                        .param(row.id)
                        .query(String.class)
                        .single())
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertRejected(row().status("CREATED"), "charges_status_check");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -100, 100_000_001})
    void rejectsAnAmountOutsideTheChargeLimits(long amount) {
        assertRejected(row().amount(amount), "charges_amount_minor_check");
    }

    @Test
    void acceptsTheLargestAmount() {
        insert(row().amount(100_000_000));
    }

    @Test
    void rejectsACurrencyOtherThanTheReal() {
        assertRejected(row().currency("USD"), "charges_currency_check");
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 10_001})
    void rejectsARefundedAmountOutsideTheCharge(long refunded) {
        assertRejected(row().amount(10_000).refunded(refunded).paid(), "charges_amount_refunded_check");
    }

    @Test
    void rejectsAnEmptyDescription() {
        assertRejected(row().description(""), "charges_description_check");
    }

    @Test
    void acceptsADescriptionOf140CharactersAndRejectsALongerOne() {
        insert(row().description("ã".repeat(140)));

        assertRejected(row().description("ã".repeat(141)), "charges_description_check");
    }

    @Test
    void acceptsAChargeWithoutDescription() {
        insert(row().description(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"order\"", "null"})
    void rejectsMetadataThatIsNotAnObject(String metadata) {
        assertRejected(row().metadata(metadata), "charges_metadata_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"fx7Kq2mP9wR4tY6uA8sD3fG5", "fx7Kq2mP9wR4tY6uA8sD3fG5hX", "fx7Kq2mP9wR4tY6uA8sD3fG5-"})
    void rejectsATransactionIdOtherThan25LettersOrDigits(String transactionId) {
        assertRejected(row().transactionId(transactionId), "charges_transaction_id_check");
    }

    @Test
    void rejectsATransactionIdAnotherTenantsChargeHolds() {
        var first = row();
        insert(first);

        assertRejected(
                row().tenant(UUID.randomUUID()).transactionId(first.transactionId), "charges_transaction_id_key");
    }

    @Test
    void rejectsAChargeThatLastsLessThanAMinute() {
        var row = row();

        assertRejected(row.expiresAt(row.createdAt.plusSeconds(59)), "charges_lifetime_check");
    }

    @Test
    void rejectsAPaidChargeWithoutItsPaymentTime() {
        assertRejected(row().status("PAID"), "charges_paid_at_check");
    }

    @Test
    void rejectsAPaymentTimeOnAChargeNotPaid() {
        assertRejected(row().paid().status("EXPIRED"), "charges_paid_at_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "PARTIALLY_REFUNDED", "REFUNDED"})
    void acceptsAPaymentTimeInEveryPaidState(String status) {
        insert(row().paid().status(status));
    }

    @Test
    void letsTheModuleRoleInsertReadAndUpdateACharge() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));
        var row = row();
        insert(row);

        var updated = jdbc.sql("UPDATE billing.charges SET status = 'EXPIRED' WHERE id = ?")
                .param(row.id)
                .update();

        assertThat(updated).isOne();
    }

    @Test
    void deniesTheModuleRoleDeletingACharge() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM billing.charges WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void showsTheModuleRoleNoChargeWithNoTenantBound() {
        insert(row());
        DatabaseRole.BILLING.actAs(jdbc);

        assertThat(visibleCharges()).isZero();
    }

    @Test
    void hidesAnotherTenantsChargeFromTheModuleRole() {
        insert(row().tenant(UUID.randomUUID()));
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleCharges()).isZero();
    }

    @Test
    void leavesAnotherTenantsChargeUnchangedByTheModuleRole() {
        var other = row().tenant(UUID.randomUUID());
        insert(other);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        var updated = jdbc.sql("UPDATE billing.charges SET status = 'EXPIRED' WHERE id = ?")
                .param(other.id)
                .update();

        assertThat(updated).isZero();
    }

    @Test
    void deniesTheModuleRoleInsertingAChargeForAnotherTenant() {
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(row().tenant(UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleMovingAChargeToAnotherTenant() {
        var row = row();
        insert(row);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE billing.charges SET tenant_id = ? WHERE id = ?")
                        .params(UUID.randomUUID(), row.id)
                        .update())
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingAChargeWithNoTenantBound() {
        DatabaseRole.BILLING.actAs(jdbc);

        assertThatThrownBy(() -> insert(row())).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void keepsATransactionIdUniqueAcrossTenantsTheModuleRoleCannotSee() {
        var other = row().tenant(UUID.randomUUID());
        insert(other);
        DatabaseRole.BILLING.actAs(jdbc, new TenantId(tenant));

        assertRejected(row().transactionId(other.transactionId), "charges_transaction_id_key");
    }

    private long visibleCharges() {
        return jdbc.sql("SELECT count(*) FROM billing.charges")
                .query(Long.class)
                .single();
    }

    private void assertRejected(Row row, String constraint) {
        assertThatThrownBy(() -> insert(row))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private Row row() {
        return new Row(tenant);
    }

    private void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, description,
                                metadata, transaction_id, br_code, expires_at, paid_at, created_at, updated_at)
                        VALUES (:id, :tenant, :status, :amount, :currency, :refunded, :description,
                                CAST(:metadata AS jsonb), :transactionId, '000201', :expiresAt, :paidAt,
                                :createdAt, :createdAt)
                        """)
                .param("id", row.id)
                .param("tenant", row.tenant)
                .param("status", row.status)
                .param("amount", row.amount)
                .param("currency", row.currency)
                .param("refunded", row.refunded)
                .param("description", row.description, Types.VARCHAR)
                .param("metadata", row.metadata)
                .param("transactionId", row.transactionId)
                .param("expiresAt", row.expiresAt.atOffset(ZoneOffset.UTC))
                .param(
                        "paidAt",
                        row.paidAt == null ? null : row.paidAt.atOffset(ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("createdAt", row.createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A valid active charge of the tenant; each method changes one column. */
    private static final class Row {

        private final UUID id = UUID.randomUUID();
        private UUID tenant;
        private String status = "ACTIVE";
        private long amount = 10_000;
        private String currency = "BRL";
        private long refunded = 0;
        private @Nullable String description = "Mensalidade de novembro";
        private String metadata = "{\"order_id\": \"ord_8842\"}";
        private String transactionId =
                UUID.randomUUID().toString().replace("-", "").substring(0, 25);
        private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        private Instant expiresAt = createdAt.plus(Duration.ofMinutes(30));
        private @Nullable Instant paidAt;

        private Row(UUID tenant) {
            this.tenant = tenant;
        }

        Row tenant(UUID value) {
            tenant = value;
            return this;
        }

        Row status(String value) {
            status = value;
            return this;
        }

        Row amount(long value) {
            amount = value;
            return this;
        }

        Row currency(String value) {
            currency = value;
            return this;
        }

        Row refunded(long value) {
            refunded = value;
            return this;
        }

        Row description(@Nullable String value) {
            description = value;
            return this;
        }

        Row metadata(String value) {
            metadata = value;
            return this;
        }

        Row transactionId(String value) {
            transactionId = value;
            return this;
        }

        Row expiresAt(Instant value) {
            expiresAt = value;
            return this;
        }

        /** Paid ten minutes after creation, as PAID. */
        Row paid() {
            status = "PAID";
            paidAt = createdAt.plus(Duration.ofMinutes(10));
            return this;
        }
    }
}
