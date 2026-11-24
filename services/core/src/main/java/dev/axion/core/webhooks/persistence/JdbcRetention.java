package dev.axion.core.webhooks.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Removes what the delivery log no longer keeps, across tenants (events and webhooks design,
 * section 8.8). Each method removes at most {@code limit} rows of what it names.
 *
 * <p>Ages are measured with the database's time, as the role's policies measure them: a row the job
 * finds is one the policies let it remove, so a delivery is never left without the attempts and
 * replays it removed, nor kept by one they refused.
 */
@Repository
public class JdbcRetention {

    private static final String EXPIRED = "now() - interval '30 days'";

    private final JdbcClient jdbc;

    public JdbcRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Removes deliveries that ended, made 30 days ago or more and with no attempt nor replay asked for
     * since and none pending, with their attempts and replays.
     *
     * @return how many deliveries were removed
     */
    public int removeDeliveries(int limit) {
        List<UUID> expired = jdbc.sql("""
                        SELECT delivery.id FROM webhooks.deliveries AS delivery
                         WHERE delivery.status <> 'PENDING' AND delivery.created_at < %1$s
                           AND NOT EXISTS (SELECT 1 FROM webhooks.attempts AS attempt
                                            WHERE attempt.delivery_id = delivery.id
                                              AND attempt.started_at >= %1$s)
                           AND NOT EXISTS (SELECT 1 FROM webhooks.replays AS replay
                                            WHERE replay.delivery_id = delivery.id
                                              AND (replay.status = 'PENDING' OR replay.requested_at >= %1$s))
                         LIMIT :limit
                        """.formatted(EXPIRED))
                .param("limit", limit)
                .query((row, number) -> row.getObject("id", UUID.class))
                .list();
        if (expired.isEmpty()) {
            return 0;
        }
        jdbc.sql("DELETE FROM webhooks.attempts WHERE delivery_id IN (:ids)")
                .param("ids", expired)
                .update();
        jdbc.sql("DELETE FROM webhooks.replays WHERE delivery_id IN (:ids)")
                .param("ids", expired)
                .update();
        return jdbc.sql("DELETE FROM webhooks.deliveries WHERE id IN (:ids)")
                .param("ids", expired)
                .update();
    }

    /**
     * Removes events that occurred 30 days ago or more and that no delivery sends any more.
     *
     * @return how many were removed
     */
    public int removeEvents(int limit) {
        return jdbc.sql("""
                        DELETE FROM webhooks.events
                         WHERE id IN (SELECT event.id FROM webhooks.events AS event
                                       WHERE event.occurred_at < %s
                                         AND NOT EXISTS (SELECT 1 FROM webhooks.deliveries AS delivery
                                                          WHERE delivery.event_id = event.id)
                                       LIMIT :limit)
                        """.formatted(EXPIRED)).param("limit", limit).update();
    }

    /**
     * Removes endpoints deleted 30 days ago or more and with no delivery left, with their secrets.
     *
     * @return how many endpoints were removed
     */
    public int removeEndpoints(int limit) {
        List<UUID> expired = jdbc.sql("""
                        SELECT endpoint.id FROM webhooks.endpoints AS endpoint
                         WHERE endpoint.deleted_at < %s
                           AND NOT EXISTS (SELECT 1 FROM webhooks.deliveries AS delivery
                                            WHERE delivery.endpoint_id = endpoint.id)
                         LIMIT :limit
                        """.formatted(EXPIRED))
                .param("limit", limit)
                .query((row, number) -> row.getObject("id", UUID.class))
                .list();
        if (expired.isEmpty()) {
            return 0;
        }
        jdbc.sql("DELETE FROM webhooks.secrets WHERE endpoint_id IN (:ids)")
                .param("ids", expired)
                .update();
        return jdbc.sql("DELETE FROM webhooks.endpoints WHERE id IN (:ids)")
                .param("ids", expired)
                .update();
    }
}
