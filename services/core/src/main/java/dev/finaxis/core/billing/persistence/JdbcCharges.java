package dev.finaxis.core.billing.persistence;

import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.domain.BrCode;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.TransactionId;
import dev.finaxis.core.shared.TenantId;
import java.sql.Types;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcCharges {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public JdbcCharges(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Inserts a new charge, or nothing if its transaction ID is taken, by a charge of any tenant: the
     * unique index sees past Row-Level Security.
     */
    public boolean insertIfTransactionIdFree(
            TenantId tenant, Charge charge, ChargeRequest request, TransactionId transactionId, BrCode brCode) {
        int inserted = jdbc.sql("""
                        INSERT INTO billing.charges
                               (id, tenant_id, status, amount_minor, currency, amount_refunded, description,
                                metadata, transaction_id, br_code, expires_at, paid_at, created_at, updated_at)
                        VALUES (:id, :tenantId, :status, :amount, :currency, :amountRefunded, :description,
                                CAST(:metadata AS jsonb), :transactionId, :brCode, :expiresAt, NULL,
                                :createdAt, :createdAt)
                        ON CONFLICT (transaction_id) DO NOTHING
                        """)
                .param("id", charge.id().value())
                .param("tenantId", tenant.value())
                .param("status", charge.status().name())
                .param("amount", charge.amount().minorUnits())
                .param("currency", charge.amount().currency().getCurrencyCode())
                .param("amountRefunded", charge.amountRefunded().minorUnits())
                .param("description", request.description(), Types.VARCHAR)
                .param("metadata", json.writeValueAsString(request.metadata()))
                .param("transactionId", transactionId.value())
                .param("brCode", brCode.text())
                .param("expiresAt", charge.expiresAt().atOffset(ZoneOffset.UTC))
                .param("createdAt", charge.createdAt().atOffset(ZoneOffset.UTC))
                .update();
        return inserted == 1;
    }
}
