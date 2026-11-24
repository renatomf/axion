package dev.axion.core.webhooks.persistence;

import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The replays a tenant asked for, as the module's role reaches them (events and webhooks design, section 8.7). */
@Repository
public class JdbcReplays {

    private final JdbcClient jdbc;

    public JdbcReplays(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** When the delivery's replays asked for after the time were asked for, oldest first. */
    public List<Instant> requestedSince(TenantId tenant, WebhookDeliveryId delivery, Instant since) {
        return jdbc.sql("""
                        SELECT requested_at FROM webhooks.replays
                         WHERE tenant_id = :tenant AND delivery_id = :delivery AND requested_at > :since
                         ORDER BY requested_at
                        """)
                .param("tenant", tenant.value())
                .param("delivery", delivery.value())
                .param("since", utc(since))
                .query((row, number) ->
                        row.getObject("requested_at", OffsetDateTime.class).toInstant())
                .list();
    }

    /** A pending replay of the delivery, which the next pass of the workers claims. */
    public void queue(UUID id, TenantId tenant, WebhookDeliveryId delivery, Instant requestedAt) {
        jdbc.sql("""
                        INSERT INTO webhooks.replays (id, tenant_id, delivery_id, status, requested_at)
                        VALUES (:id, :tenant, :delivery, 'PENDING', :requestedAt)
                        """)
                .param("id", id)
                .param("tenant", tenant.value())
                .param("delivery", delivery.value())
                .param("requestedAt", utc(requestedAt))
                .update();
    }

    /**
     * Cancels the pending replays of the endpoint's deliveries, those a worker holds included: the
     * request in flight is still recorded, as sent.
     *
     * @return how many were canceled
     */
    public int cancelPending(TenantId tenant, WebhookEndpointId endpoint) {
        return jdbc.sql("""
                        UPDATE webhooks.replays AS replay
                           SET status = 'CANCELED', leased_until = NULL
                          FROM webhooks.deliveries AS delivery
                         WHERE replay.tenant_id = :tenant AND replay.status = 'PENDING'
                           AND delivery.id = replay.delivery_id AND delivery.endpoint_id = :endpoint
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
