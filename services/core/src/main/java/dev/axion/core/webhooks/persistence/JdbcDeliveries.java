package dev.axion.core.webhooks.persistence;

import dev.axion.core.events.PublicEvent;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.WebhookEndpointId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The public events the module sends and their deliveries, one per event and endpoint. */
@Repository
public class JdbcDeliveries {

    private final JdbcClient jdbc;

    public JdbcDeliveries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event, once.
     *
     * @return whether it was recorded now; {@code false} if it was already, by an earlier handling
     */
    public boolean insertEvent(TenantId tenant, PublicEvent event) {
        return jdbc.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (:id, :tenant, :type, :body, :occurredAt)
                        ON CONFLICT (id) DO NOTHING
                        """)
                        .param("id", event.id().value())
                        .param("tenant", tenant.value())
                        .param("type", event.type())
                        .param("body", event.bodyBytes())
                        .param("occurredAt", utc(event.occurredAt()))
                        .update()
                == 1;
    }

    /**
     * The tenant's endpoints that receive the type: enabled, not deleted, and subscribed to it or to
     * every type. Locked against changes until the transaction ends, so an endpoint disabled or
     * deleted meanwhile cancels the deliveries recorded for it instead of missing them.
     */
    public List<WebhookEndpointId> lockReceivers(TenantId tenant, String type) {
        return jdbc.sql("""
                        SELECT id FROM webhooks.endpoints
                         WHERE tenant_id = :tenant AND deleted_at IS NULL AND status = 'ENABLED'
                           AND (:type = ANY (enabled_events) OR '*' = ANY (enabled_events))
                         ORDER BY id
                           FOR SHARE
                        """)
                .param("tenant", tenant.value())
                .param("type", type)
                .query((row, number) -> new WebhookEndpointId(row.getObject("id", UUID.class)))
                .list();
    }

    /** A pending delivery of the event to the endpoint, due at once. */
    public void insertDelivery(UUID id, TenantId tenant, WebhookEndpointId endpoint, PublicEvent event, Instant now) {
        jdbc.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at,
                                created_at)
                        VALUES (:id, :tenant, :endpoint, :event, 'PENDING', 0, :now, :now)
                        """)
                .param("id", id)
                .param("tenant", tenant.value())
                .param("endpoint", endpoint.value())
                .param("event", event.id().value())
                .param("now", utc(now))
                .update();
    }

    /**
     * Cancels the endpoint's pending deliveries, those a worker holds included: the result of an
     * attempt in flight then changes nothing.
     *
     * @return how many were canceled
     */
    public int cancelPending(TenantId tenant, WebhookEndpointId endpoint) {
        return jdbc.sql("""
                        UPDATE webhooks.deliveries
                           SET status = 'CANCELED', next_attempt_at = NULL, leased_until = NULL
                         WHERE tenant_id = :tenant AND endpoint_id = :endpoint AND status = 'PENDING'
                        """)
                .param("tenant", tenant.value())
                .param("endpoint", endpoint.value())
                .update();
    }

    // Bound as an offset, so the JVM's time zone never shifts the stored instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
