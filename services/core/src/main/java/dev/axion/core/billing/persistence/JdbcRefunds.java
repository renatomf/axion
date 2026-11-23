package dev.axion.core.billing.persistence;

import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.RefundId;
import dev.axion.core.billing.RefundStatus;
import dev.axion.core.billing.RefundView;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.billing.domain.Refund;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRefunds {

    private final JdbcClient jdbc;

    public JdbcRefunds(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The charge's refunds, in the order they were made: UUIDv7 IDs grow with time. */
    public List<Refund> ofCharge(ChargeId charge) {
        return jdbc.sql("""
                        SELECT id, payment_id, amount_minor, created_at
                          FROM billing.refunds
                         WHERE charge_id = :charge
                         ORDER BY id
                        """)
                .param("charge", charge.value())
                .query((row, rowNumber) -> new Refund(
                        new RefundId(row.getObject("id", UUID.class)),
                        new PaymentId(row.getObject("payment_id", UUID.class)),
                        Money.ofMinor(row.getLong("amount_minor"), Charge.CURRENCY),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** The charge's refunds, oldest first, as the API shows them; a charge has at most {@code Charge.MAX_REFUNDS}. */
    public List<RefundView> views(ChargeId charge) {
        return jdbc.sql("""
                        SELECT id, payment_id, amount_minor, status, created_at
                          FROM billing.refunds
                         WHERE charge_id = :charge
                         ORDER BY id
                        """)
                .param("charge", charge.value())
                .query((row, rowNumber) -> new RefundView(
                        new RefundId(row.getObject("id", UUID.class)),
                        charge,
                        new PaymentId(row.getObject("payment_id", UUID.class)),
                        Money.ofMinor(row.getLong("amount_minor"), Charge.CURRENCY),
                        RefundStatus.valueOf(row.getString("status")),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Records a refund of the charge, made by {@code entry}; a refund succeeds at once in phase 1. */
    public void insert(TenantId tenant, ChargeId charge, Refund refund, EntryId entry, String correlationId) {
        jdbc.sql("""
                        INSERT INTO billing.refunds
                               (id, tenant_id, charge_id, payment_id, amount_minor, status, entry_id,
                                correlation_id, created_at)
                        VALUES (:id, :tenantId, :chargeId, :paymentId, :amount, :status, :entryId,
                                :correlationId, :createdAt)
                        """)
                .param("id", refund.id().value())
                .param("tenantId", tenant.value())
                .param("chargeId", charge.value())
                .param("paymentId", refund.payment().value())
                .param("amount", refund.amount().minorUnits())
                .param("status", RefundStatus.SUCCEEDED.name())
                .param("entryId", entry.value())
                .param("correlationId", correlationId)
                .param("createdAt", refund.createdAt().atOffset(ZoneOffset.UTC))
                .update();
    }
}
