package dev.axion.core.webhooks;

import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deliveries arranged and read by the database's owner, for tests of the queue and of what an
 * endpoint's changes do to deliveries: each with an event of its own, committed at once.
 */
public final class TestDeliveries {

    private final JdbcClient owner;

    public TestDeliveries(JdbcClient owner) {
        this.owner = owner;
    }

    /** A delivery to the endpoint, due now. */
    public UUID pending(TenantId tenant, WebhookEndpointId endpoint) {
        return insert(tenant, endpoint, "PENDING", 0, "now()", "NULL");
    }

    /** A delivery to the endpoint, due at the time, held by a worker until the lease's end if any. */
    public UUID pending(TenantId tenant, WebhookEndpointId endpoint, Instant due, @Nullable Instant leasedUntil) {
        return insert(
                tenant, endpoint, "PENDING", 0, timestamp(due), leasedUntil == null ? "NULL" : timestamp(leasedUntil));
    }

    /** A delivery to the endpoint that was received. */
    public UUID succeeded(TenantId tenant, WebhookEndpointId endpoint) {
        return insert(tenant, endpoint, "SUCCEEDED", 1, "NULL", "NULL");
    }

    /** The body of the delivery's event, as stored. */
    public String body(UUID delivery) {
        return owner.sql("""
                        SELECT convert_from(e.body, 'UTF8') FROM webhooks.deliveries d
                          JOIN webhooks.events e ON e.id = d.event_id
                         WHERE d.id = ?
                        """).param(delivery).query(String.class).single();
    }

    public int attemptCount(UUID delivery) {
        return owner.sql("SELECT attempt_count FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(Integer.class)
                .single();
    }

    /** When the delivery's next attempt is due; empty once it is no longer pending. */
    public Optional<Instant> nextAttemptAt(UUID delivery) {
        return Optional.ofNullable(owner.sql("SELECT next_attempt_at FROM webhooks.deliveries WHERE id = ?")
                        .param(delivery)
                        .query(OffsetDateTime.class)
                        .single())
                .map(OffsetDateTime::toInstant);
    }

    public boolean leased(UUID delivery) {
        return owner.sql("SELECT leased_until IS NOT NULL FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(Boolean.class)
                .single();
    }

    public String status(UUID delivery) {
        return owner.sql("SELECT status FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(String.class)
                .single();
    }

    // The times are SQL expressions, such as now(), or NULL.
    private UUID insert(
            TenantId tenant,
            WebhookEndpointId endpoint,
            String status,
            int attempts,
            String nextAttemptAt,
            String leasedUntil) {
        UUID event = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', convert_to(?, 'UTF8'), now())
                        """)
                .params(event, tenant.value(), "{\"id\":\"" + new EventId(event).publicId() + "\"}")
                .update();
        UUID delivery = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at,
                                leased_until, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, %s, %s, now())
                        """.formatted(nextAttemptAt, leasedUntil))
                .params(delivery, tenant.value(), endpoint.value(), event, status, attempts)
                .update();
        return delivery;
    }

    private static String timestamp(Instant instant) {
        return "'" + instant + "'::timestamptz";
    }
}
