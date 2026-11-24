package dev.axion.core.webhooks.persistence;

import dev.axion.core.webhooks.domain.EndpointHealth;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The delivery queue, across tenants (events and webhooks design, section 8.2). */
@Repository
public class JdbcQueue {

    private final JdbcClient jdbc;

    public JdbcQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Leases at most {@code limit} due deliveries until {@code leasedUntil}, oldest first, and at most
     * {@code perEndpoint} of one endpoint at a time, counting those already held: an endpoint at its
     * limit holds no worker that another endpoint could use. A delivery whose lease has passed is
     * due again, so one a worker dropped is sent anew.
     */
    public List<ClaimedDelivery> claim(Instant now, Instant leasedUntil, int limit, int perEndpoint) {
        // Claims follow one another, so each counts the deliveries every earlier claim holds; SKIP
        // LOCKED passes over a delivery whose result is being recorded.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('webhooks.deliveries'))")
                .query()
                .singleRow();
        return jdbc.sql("""
                        WITH held AS (
                            SELECT endpoint_id, count(*) AS leased FROM webhooks.deliveries
                             WHERE status = 'PENDING' AND leased_until > :now
                             GROUP BY endpoint_id),
                        waiting AS (
                            SELECT endpoint_id, min(next_attempt_at) AS oldest FROM webhooks.deliveries
                             WHERE status = 'PENDING' AND next_attempt_at <= :now
                               AND (leased_until IS NULL OR leased_until <= :now)
                             GROUP BY endpoint_id),
                        claimed AS (
                            SELECT due.id FROM waiting
                              LEFT JOIN held USING (endpoint_id)
                             CROSS JOIN LATERAL (
                                SELECT id, next_attempt_at FROM webhooks.deliveries
                                 WHERE endpoint_id = waiting.endpoint_id AND status = 'PENDING'
                                   AND next_attempt_at <= :now AND (leased_until IS NULL OR leased_until <= :now)
                                 ORDER BY next_attempt_at
                                 LIMIT greatest(:perEndpoint - coalesce(held.leased, 0), 0)
                                   FOR UPDATE SKIP LOCKED) AS due
                             ORDER BY waiting.oldest, due.next_attempt_at
                             LIMIT :limit)
                        UPDATE webhooks.deliveries AS delivery
                           SET leased_until = :leasedUntil
                          FROM webhooks.endpoints AS endpoint, webhooks.events AS event
                         WHERE delivery.id IN (SELECT id FROM claimed)
                           AND endpoint.id = delivery.endpoint_id AND event.id = delivery.event_id
                        RETURNING delivery.id, delivery.endpoint_id, delivery.event_id, endpoint.url,
                                  event.body, delivery.attempt_count, delivery.leased_until,
                                  ARRAY(SELECT secret.ciphertext FROM webhooks.secrets AS secret
                                         WHERE secret.endpoint_id = delivery.endpoint_id
                                           AND (secret.expires_at IS NULL OR secret.expires_at > :now)
                                         ORDER BY secret.expires_at DESC NULLS FIRST) AS secrets
                        """)
                .param("now", utc(now))
                .param("leasedUntil", utc(leasedUntil))
                .param("limit", limit)
                .param("perEndpoint", perEndpoint)
                .query(JdbcQueue::claimed)
                .list();
    }

    /**
     * Records the last attempt: the delivery succeeded or is dead, its lease released. Nothing
     * changes if the delivery is no longer the worker's: canceled meanwhile, or its lease passed and
     * another worker claimed it.
     *
     * @param status {@code SUCCEEDED} or {@code DEAD}
     * @return whether the delivery was still the worker's
     */
    public boolean finish(ClaimedDelivery delivery, String status) {
        return jdbc.sql("""
                        UPDATE webhooks.deliveries
                           SET status = :status, attempt_count = attempt_count + 1,
                               next_attempt_at = NULL, leased_until = NULL
                         WHERE id = :id AND status = 'PENDING' AND leased_until = :leasedUntil
                        """)
                        .param("status", status)
                        .param("id", delivery.id())
                        .param("leasedUntil", utc(delivery.leasedUntil()))
                        .update()
                == 1;
    }

    /**
     * Records a failed attempt that another follows: the delivery stays pending, due at the time, its
     * lease released. Nothing changes if the delivery is no longer the worker's, as for {@link
     * #finish}.
     *
     * @return whether the delivery was still the worker's
     */
    public boolean retry(ClaimedDelivery delivery, Instant at) {
        return jdbc.sql("""
                        UPDATE webhooks.deliveries
                           SET attempt_count = attempt_count + 1, next_attempt_at = :at, leased_until = NULL
                         WHERE id = :id AND status = 'PENDING' AND leased_until = :leasedUntil
                        """)
                        .param("at", utc(at))
                        .param("id", delivery.id())
                        .param("leasedUntil", utc(delivery.leasedUntil()))
                        .update()
                == 1;
    }

    /**
     * Locks the endpoint until the transaction ends and reads its health; empty if it no longer
     * receives, disabled or deleted, as the role's policies show it.
     */
    public Optional<EndpointHealth> lockEndpoint(UUID endpoint) {
        return jdbc.sql("""
                        SELECT failing_since, consecutive_failures FROM webhooks.endpoints
                         WHERE id = :id
                           FOR UPDATE
                        """)
                .param("id", endpoint)
                .query((row, number) -> new EndpointHealth(
                        Optional.ofNullable(row.getObject("failing_since", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant),
                        row.getInt("consecutive_failures")))
                .optional();
    }

    /** Stores the health of an endpoint the transaction locked. */
    public void recordHealth(UUID endpoint, EndpointHealth health) {
        jdbc.sql("""
                        UPDATE webhooks.endpoints
                           SET failing_since = :failingSince, consecutive_failures = :failures
                         WHERE id = :id
                        """)
                .param("failingSince", health.failingSince().map(JdbcQueue::utc).orElse(null))
                .param("failures", health.consecutiveFailures())
                .param("id", endpoint)
                .update();
    }

    private static ClaimedDelivery claimed(ResultSet row, int rowNumber) throws SQLException {
        return new ClaimedDelivery(
                row.getObject("id", UUID.class),
                row.getObject("endpoint_id", UUID.class),
                row.getObject("event_id", UUID.class),
                URI.create(row.getString("url")),
                new String(row.getBytes("body"), StandardCharsets.UTF_8),
                List.of((byte[][]) row.getArray("secrets").getArray()),
                row.getInt("attempt_count"),
                row.getObject("leased_until", OffsetDateTime.class).toInstant());
    }

    // Bound as an offset, so the JVM's time zone never shifts the stored instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
