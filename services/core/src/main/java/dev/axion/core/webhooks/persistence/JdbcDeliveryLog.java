package dev.axion.core.webhooks.persistence;

import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.AttemptView;
import dev.axion.core.webhooks.DeliveryFilter;
import dev.axion.core.webhooks.DeliveryStatus;
import dev.axion.core.webhooks.DeliveryView;
import dev.axion.core.webhooks.WebhookAttemptId;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.AttemptFailure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A tenant's delivery log, read as the module's role (events and webhooks design, section 8.7). */
@Repository
public class JdbcDeliveryLog {

    private static final String SELECT = """
            SELECT delivery.id, delivery.endpoint_id, delivery.event_id, event.type, delivery.status,
                   delivery.attempt_count, delivery.next_attempt_at, delivery.created_at
              FROM webhooks.deliveries AS delivery
              JOIN webhooks.events AS event ON event.id = delivery.event_id
             WHERE delivery.tenant_id = :tenant AND delivery.endpoint_id = :endpoint""";

    private final JdbcClient jdbc;

    public JdbcDeliveryLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether the tenant has or had the endpoint: a deleted one keeps its log until its retention ends. */
    public boolean hasEndpoint(TenantId tenant, WebhookEndpointId endpoint) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM webhooks.endpoints WHERE tenant_id = :tenant AND id = :id)")
                .param("tenant", tenant.value())
                .param("id", endpoint.value())
                .query(Boolean.class)
                .single();
    }

    /**
     * The endpoint's deliveries the filter keeps, newest first.
     *
     * @param after the last delivery of the previous page; only older ones follow it
     */
    public List<DeliveryView> list(
            TenantId tenant,
            WebhookEndpointId endpoint,
            DeliveryFilter filter,
            Optional<WebhookDeliveryId> after,
            int count) {
        var sql = new StringBuilder(SELECT);
        Map<String, Object> params = new HashMap<>();
        params.put("tenant", tenant.value());
        params.put("endpoint", endpoint.value());
        filter.status().ifPresent(status -> {
            sql.append(" AND delivery.status = :status");
            params.put("status", status.name());
        });
        filter.eventType().ifPresent(type -> {
            sql.append(" AND event.type = :type");
            params.put("type", type);
        });
        after.ifPresent(last -> {
            sql.append(" AND delivery.id < :after");
            params.put("after", last.value());
        });
        sql.append(" ORDER BY delivery.id DESC LIMIT :count");
        params.put("count", count);
        return jdbc.sql(sql.toString())
                .params(params)
                .query(JdbcDeliveryLog::delivery)
                .list();
    }

    public Optional<DeliveryView> find(TenantId tenant, WebhookEndpointId endpoint, WebhookDeliveryId id) {
        return jdbc.sql(SELECT + " AND delivery.id = :id")
                .param("tenant", tenant.value())
                .param("endpoint", endpoint.value())
                .param("id", id.value())
                .query(JdbcDeliveryLog::delivery)
                .optional();
    }

    /** Every request sent for the delivery, oldest first. */
    public List<AttemptView> attempts(TenantId tenant, WebhookDeliveryId delivery) {
        return jdbc.sql("""
                        SELECT id, started_at, duration_ms, status_code, failure, response_excerpt
                          FROM webhooks.attempts
                         WHERE tenant_id = :tenant AND delivery_id = :delivery
                         ORDER BY started_at, id
                        """)
                .param("tenant", tenant.value())
                .param("delivery", delivery.value())
                .query(JdbcDeliveryLog::attempt)
                .list();
    }

    private static DeliveryView delivery(ResultSet row, int rowNumber) throws SQLException {
        return new DeliveryView(
                new WebhookDeliveryId(row.getObject("id", UUID.class)),
                new WebhookEndpointId(row.getObject("endpoint_id", UUID.class)),
                new EventId(row.getObject("event_id", UUID.class)),
                row.getString("type"),
                DeliveryStatus.valueOf(row.getString("status")),
                row.getInt("attempt_count"),
                Optional.ofNullable(row.getObject("next_attempt_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    // The table's check holds exactly one of a status code and a failure.
    private static AttemptView attempt(ResultSet row, int rowNumber) throws SQLException {
        String failure = row.getString("failure");
        Attempt outcome = failure == null
                ? new Attempt.Answered(row.getInt("status_code"))
                : new Attempt.Failed(AttemptFailure.valueOf(failure));
        return new AttemptView(
                new WebhookAttemptId(row.getObject("id", UUID.class)),
                row.getObject("started_at", OffsetDateTime.class).toInstant(),
                Duration.ofMillis(row.getInt("duration_ms")),
                outcome,
                Optional.ofNullable(row.getString("response_excerpt")));
    }
}
