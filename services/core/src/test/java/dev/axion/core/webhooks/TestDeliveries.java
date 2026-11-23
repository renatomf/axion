package dev.axion.core.webhooks;

import dev.axion.core.shared.TenantId;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deliveries arranged by the database's owner, for tests of what an endpoint's changes do to them:
 * each with an event of its own, committed at once.
 */
public final class TestDeliveries {

    private final JdbcClient owner;

    public TestDeliveries(JdbcClient owner) {
        this.owner = owner;
    }

    /** A delivery to the endpoint, due now. */
    public UUID pending(TenantId tenant, WebhookEndpointId endpoint) {
        return insert(tenant, endpoint, "PENDING", 0, "now()");
    }

    /** A delivery to the endpoint that was received. */
    public UUID succeeded(TenantId tenant, WebhookEndpointId endpoint) {
        return insert(tenant, endpoint, "SUCCEEDED", 1, "NULL");
    }

    public String status(UUID delivery) {
        return owner.sql("SELECT status FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(String.class)
                .single();
    }

    private UUID insert(
            TenantId tenant, WebhookEndpointId endpoint, String status, int attempts, String nextAttemptAt) {
        UUID event = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', convert_to('{}', 'UTF8'), now())
                        """).params(event, tenant.value()).update();
        UUID delivery = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, %s, now())
                        """.formatted(nextAttemptAt))
                .params(delivery, tenant.value(), endpoint.value(), event, status, attempts)
                .update();
        return delivery;
    }
}
