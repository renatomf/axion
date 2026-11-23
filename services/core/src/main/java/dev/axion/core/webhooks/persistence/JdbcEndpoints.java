package dev.axion.core.webhooks.persistence;

import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEndpoints {

    private static final String SELECT = """
            SELECT id, url, description, enabled_events, status, disabled_reason, created_at
              FROM webhooks.endpoints
            """;

    private final JdbcClient jdbc;

    public JdbcEndpoints(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Without it, two requests could each count 15 endpoints and both insert a 16th. Advisory, since
    // this schema has no tenant row to lock; released when the transaction ends.
    public void lockTenantEndpoints(TenantId tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('webhooks.endpoints'), hashtext(CAST(:tenant AS text)))")
                .param("tenant", tenant.value())
                .query()
                .singleRow();
    }

    public int count(TenantId tenant) {
        return jdbc.sql("SELECT count(*) FROM webhooks.endpoints WHERE tenant_id = :tenant")
                .param("tenant", tenant.value())
                .query(Integer.class)
                .single();
    }

    public void insert(TenantId tenant, EndpointView endpoint) {
        jdbc.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, url, description, enabled_events, status, disabled_reason,
                                created_at, updated_at)
                        VALUES (:id, :tenant, :url, :description, :events, :status, :reason,
                                :createdAt, :createdAt)
                        """)
                .param("id", endpoint.id().value())
                .param("tenant", tenant.value())
                .param("url", endpoint.url())
                .param("description", endpoint.description().orElse(null))
                .param("events", sortedTypes(endpoint))
                .param("status", endpoint.status().name())
                .param(
                        "reason",
                        endpoint.disabledReason().map(DisabledReason::name).orElse(null))
                .param("createdAt", utc(endpoint.createdAt()))
                .update();
    }

    public void insertSecret(
            UUID id, TenantId tenant, WebhookEndpointId endpoint, byte[] ciphertext, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO webhooks.secrets (id, endpoint_id, tenant_id, ciphertext, created_at)
                        VALUES (:id, :endpoint, :tenant, :ciphertext, :createdAt)
                        """)
                .param("id", id)
                .param("endpoint", endpoint.value())
                .param("tenant", tenant.value())
                .param("ciphertext", ciphertext)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public Optional<EndpointView> find(TenantId tenant, WebhookEndpointId id) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND id = :id")
                .param("tenant", tenant.value())
                .param("id", id.value())
                .query(JdbcEndpoints::view)
                .optional();
    }

    /** Locked until the transaction ends, so a change is made to the endpoint as it is. */
    public Optional<EndpointView> lock(TenantId tenant, WebhookEndpointId id) {
        return jdbc.sql(SELECT + " WHERE tenant_id = :tenant AND id = :id FOR UPDATE")
                .param("tenant", tenant.value())
                .param("id", id.value())
                .query(JdbcEndpoints::view)
                .optional();
    }

    public void update(TenantId tenant, EndpointView endpoint, Instant updatedAt) {
        jdbc.sql("""
                        UPDATE webhooks.endpoints
                           SET url = :url, description = :description, enabled_events = :events,
                               status = :status, disabled_reason = :reason, updated_at = :updatedAt
                         WHERE tenant_id = :tenant AND id = :id
                        """)
                .param("id", endpoint.id().value())
                .param("tenant", tenant.value())
                .param("url", endpoint.url())
                .param("description", endpoint.description().orElse(null))
                .param("events", sortedTypes(endpoint))
                .param("status", endpoint.status().name())
                .param(
                        "reason",
                        endpoint.disabledReason().map(DisabledReason::name).orElse(null))
                .param("updatedAt", utc(updatedAt))
                .update();
    }

    /**
     * Newest first, by ID: a UUIDv7 starts with its creation time.
     *
     * @param after the last endpoint of the previous page; only older ones follow it
     */
    public List<EndpointView> list(TenantId tenant, Optional<WebhookEndpointId> after, int count) {
        StringBuilder sql = new StringBuilder(SELECT).append(" WHERE tenant_id = :tenant");
        Map<String, Object> params = new HashMap<>();
        params.put("tenant", tenant.value());
        after.ifPresent(last -> {
            sql.append(" AND id < :after");
            params.put("after", last.value());
        });
        sql.append(" ORDER BY id DESC LIMIT :count");
        params.put("count", count);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcEndpoints::view)
                .list();
    }

    private static EndpointView view(ResultSet row, int rowNumber) throws SQLException {
        String[] types = (String[]) row.getArray("enabled_events").getArray();
        return new EndpointView(
                new WebhookEndpointId(row.getObject("id", UUID.class)),
                row.getString("url"),
                Optional.ofNullable(row.getString("description")),
                Set.of(types),
                EndpointStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getString("disabled_reason")).map(DisabledReason::valueOf),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // Sorted, so stored types do not depend on set iteration order.
    private static String[] sortedTypes(EndpointView endpoint) {
        return endpoint.enabledEvents().stream().sorted().toArray(String[]::new);
    }

    // Bound as an offset, so the JVM's time zone never shifts the stored instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
