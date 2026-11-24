package dev.axion.core.webhooks;

import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.domain.EndpointHealth;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Deliveries arranged and read by the database's owner, for tests of the queue and of what an
 * endpoint's changes do to deliveries: each with an event of its own, committed at once. Also how
 * an endpoint has fared, which its deliveries' attempts change.
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

    /** An attempt as the log stores it. */
    public record RecordedAttempt(
            Instant startedAt,
            int durationMs,
            @Nullable Integer statusCode,
            @Nullable String failure,
            @Nullable String responseExcerpt) {}

    /** The delivery's attempts in the log, oldest first. */
    public List<RecordedAttempt> attempts(UUID delivery) {
        return owner.sql("""
                        SELECT started_at, duration_ms, status_code, failure, response_excerpt
                          FROM webhooks.attempts WHERE delivery_id = ? ORDER BY started_at, id
                        """)
                .param(delivery)
                .query((row, number) -> new RecordedAttempt(
                        row.getObject("started_at", OffsetDateTime.class).toInstant(),
                        row.getInt("duration_ms"),
                        row.getObject("status_code", Integer.class),
                        row.getString("failure"),
                        row.getString("response_excerpt")))
                .list();
    }

    /** How the endpoint has fared since its last success, as stored. */
    public EndpointHealth health(WebhookEndpointId endpoint) {
        return owner.sql("SELECT failing_since, consecutive_failures FROM webhooks.endpoints WHERE id = ?")
                .param(endpoint.value())
                .query((row, number) -> new EndpointHealth(
                        Optional.ofNullable(row.getObject("failing_since", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant),
                        row.getInt("consecutive_failures")))
                .single();
    }

    /** Arranges the endpoint as failing since the time, with as many attempts failed since. */
    public void failing(WebhookEndpointId endpoint, Instant since, int failures) {
        owner.sql("UPDATE webhooks.endpoints SET failing_since = ?, consecutive_failures = ? WHERE id = ?")
                .params(since.atOffset(ZoneOffset.UTC), failures, endpoint.value())
                .update();
    }

    /**
     * A delivery with the ID, of an event of the type, for tests of the log that need deliveries in
     * order. A pending one is due far ahead, so no worker claims it.
     */
    public UUID logged(UUID id, TenantId tenant, WebhookEndpointId endpoint, String type, String status) {
        boolean pending = status.equals("PENDING");
        return insert(
                id,
                type,
                tenant,
                endpoint,
                status,
                pending ? 0 : 1,
                pending ? "'2999-01-01T00:00:00Z'::timestamptz" : "NULL",
                "NULL");
    }

    /** An attempt of the delivery in the log, as the workers record it. */
    public void attempt(
            UUID delivery,
            TenantId tenant,
            Instant startedAt,
            int durationMs,
            @Nullable Integer statusCode,
            @Nullable String failure,
            @Nullable String excerpt) {
        owner.sql("""
                        INSERT INTO webhooks.attempts
                               (id, tenant_id, delivery_id, started_at, duration_ms, status_code, failure,
                                response_excerpt)
                        VALUES (gen_random_uuid(), :tenant, :delivery, :startedAt, :durationMs, :statusCode,
                                :failure, :excerpt)
                        """)
                .param("tenant", tenant.value())
                .param("delivery", delivery)
                .param("startedAt", startedAt.atOffset(ZoneOffset.UTC))
                .param("durationMs", durationMs)
                .param("statusCode", statusCode)
                .param("failure", failure)
                .param("excerpt", excerpt)
                .update();
    }

    private UUID insert(
            TenantId tenant,
            WebhookEndpointId endpoint,
            String status,
            int attempts,
            String nextAttemptAt,
            String leasedUntil) {
        return insert(UUID.randomUUID(), "charge.paid", tenant, endpoint, status, attempts, nextAttemptAt, leasedUntil);
    }

    // The times are SQL expressions, such as now(), or NULL.
    private UUID insert(
            UUID delivery,
            String type,
            TenantId tenant,
            WebhookEndpointId endpoint,
            String status,
            int attempts,
            String nextAttemptAt,
            String leasedUntil) {
        UUID event = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, ?, convert_to(?, 'UTF8'), now())
                        """)
                .params(event, tenant.value(), type, "{\"id\":\"" + new EventId(event).publicId() + "\"}")
                .update();
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
