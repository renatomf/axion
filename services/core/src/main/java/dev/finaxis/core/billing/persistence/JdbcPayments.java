package dev.finaxis.core.billing.persistence;

import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.PaymentOutcome;
import dev.finaxis.core.billing.ReviewReason;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.Payment;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPayments {

    private final JdbcClient jdbc;

    public JdbcPayments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The charge's payments, in the order they were recorded: UUIDv7 IDs grow with time. */
    public List<Payment> ofCharge(ChargeId charge) {
        return jdbc.sql("""
                        SELECT id, end_to_end_id, amount_minor, accepted_at, outcome, review_reason
                          FROM billing.payments
                         WHERE charge_id = :charge
                         ORDER BY id
                        """)
                .param("charge", charge.value())
                .query((row, rowNumber) -> payment(row))
                .list();
    }

    /**
     * Records a payment of the charge, credited to the tenant by {@code entry}. A second payment
     * with its EndToEndId, or a second settling payment of the charge, is refused by the database.
     */
    public void insert(TenantId tenant, ChargeId charge, Payment payment, EntryId entry, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO billing.payments
                               (id, tenant_id, charge_id, end_to_end_id, amount_minor, amount_refunded,
                                accepted_at, outcome, review_reason, entry_id, created_at)
                        VALUES (:id, :tenantId, :chargeId, :endToEndId, :amount, 0, :acceptedAt, :outcome,
                                :reviewReason, :entryId, :createdAt)
                        """)
                .param("id", payment.id().value())
                .param("tenantId", tenant.value())
                .param("chargeId", charge.value())
                .param("endToEndId", payment.endToEndId())
                .param("amount", payment.amount().minorUnits())
                .param("acceptedAt", payment.acceptedAt().atOffset(ZoneOffset.UTC))
                .param("outcome", payment.outcome().name())
                .param(
                        "reviewReason",
                        payment.reviewReason().map(ReviewReason::name).orElse(null),
                        Types.VARCHAR)
                .param("entryId", entry.value())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Payment payment(ResultSet row) throws SQLException {
        return new Payment(
                new PaymentId(row.getObject("id", UUID.class)),
                row.getString("end_to_end_id"),
                Money.ofMinor(row.getLong("amount_minor"), Charge.CURRENCY),
                row.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                PaymentOutcome.valueOf(row.getString("outcome")),
                Optional.ofNullable(row.getString("review_reason")).map(ReviewReason::valueOf));
    }
}
