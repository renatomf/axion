package dev.finaxis.core.billing.persistence;

import dev.finaxis.core.billing.ChargeFilter;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.ChargeView;
import dev.finaxis.core.billing.domain.BrCode;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.TransactionId;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class JdbcCharges {

    private static final TypeReference<Map<String, String>> METADATA = new TypeReference<>() {};

    private static final String COLUMNS = """
            id, status, amount_minor, currency, amount_refunded, description, metadata::text AS metadata,
            transaction_id, br_code, expires_at, paid_at, created_at
            """;

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

    /**
     * The charge, if the tenant's. Row-Level Security also hides the other tenants' charges, should a
     * query forget its filter.
     *
     * @param tenant the bound tenant, with its environment, which the row does not store
     */
    public Optional<ChargeView> find(TenantContext.Binding tenant, ChargeId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM billing.charges WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.tenant().value())
                .param("id", id.value())
                .query((row, rowNumber) -> view(row, tenant.environment()))
                .optional();
    }

    /**
     * The tenant's charges that match the filter, newest first, up to {@code count}.
     *
     * @param after the last charge of the previous page; only older ones follow it
     */
    public List<ChargeView> list(
            TenantContext.Binding tenant, ChargeFilter filter, Optional<ChargeId> after, int count) {
        // Keyset pagination on (tenant_id, id), or (tenant_id, status, id) with a status: a UUIDv7
        // grows with time, so the newest come first, and a deep page costs the same as the first.
        var sql = new StringBuilder("SELECT " + COLUMNS + " FROM billing.charges WHERE tenant_id = :tenant");
        var params = new HashMap<String, Object>();
        params.put("tenant", tenant.tenant().value());
        filter.status().ifPresent(status -> {
            sql.append(" AND status = :status");
            params.put("status", status.name());
        });
        filter.createdFrom().ifPresent(from -> {
            sql.append(" AND created_at >= :createdFrom");
            params.put("createdFrom", from.atOffset(ZoneOffset.UTC));
        });
        filter.createdBefore().ifPresent(before -> {
            sql.append(" AND created_at < :createdBefore");
            params.put("createdBefore", before.atOffset(ZoneOffset.UTC));
        });
        after.ifPresent(last -> {
            sql.append(" AND id < :after");
            params.put("after", last.value());
        });
        sql.append(" ORDER BY id DESC LIMIT :count");
        params.put("count", count);
        return jdbc.sql(sql.toString())
                .params(params)
                .query((row, rowNumber) -> view(row, tenant.environment()))
                .list();
    }

    private ChargeView view(ResultSet row, Environment environment) throws SQLException {
        Currency currency = Currency.getInstance(row.getString("currency"));
        return new ChargeView(
                new ChargeId(row.getObject("id", UUID.class)),
                ChargeStatus.valueOf(row.getString("status")),
                Money.ofMinor(row.getLong("amount_minor"), currency),
                Money.ofMinor(row.getLong("amount_refunded"), currency),
                row.getString("description"),
                json.readValue(row.getString("metadata"), METADATA),
                row.getString("transaction_id"),
                row.getString("br_code"),
                instant(row, "expires_at"),
                nullableInstant(row, "paid_at"),
                instant(row, "created_at"),
                environment);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
