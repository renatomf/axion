package dev.axion.core.webhooks.persistence;

import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEndpoints {

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
        // Sorted, so stored types do not depend on set iteration order.
        String[] types = endpoint.enabledEvents().stream().sorted().toArray(String[]::new);
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
                .param("events", types)
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

    // Bound as an offset, so the JVM's time zone never shifts the stored instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
