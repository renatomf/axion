package dev.finaxis.simulator.delivery;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DeliveryRepository {

    private final JdbcClient jdbc;

    DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A delivery the dispatcher holds a lease on, to send. A class, not a record: it holds the message's bytes. */
    static final class Due {

        private final UUID id;
        private final String endToEndId;
        private final String messageId;
        private final byte[] message;
        private final int attempts;
        private final Instant createdAt;

        Due(UUID id, String endToEndId, String messageId, byte[] message, int attempts, Instant createdAt) {
            this.id = id;
            this.endToEndId = endToEndId;
            this.messageId = messageId;
            this.message = message.clone();
            this.attempts = attempts;
            this.createdAt = createdAt;
        }

        UUID id() {
            return id;
        }

        String endToEndId() {
            return endToEndId;
        }

        String messageId() {
            return messageId;
        }

        byte[] message() {
            return message.clone();
        }

        /** The attempts made before this one. */
        int attempts() {
            return attempts;
        }

        Instant createdAt() {
            return createdAt;
        }
    }

    void insert(UUID id, String endToEndId, String messageId, byte[] message, Instant createdAt) {
        var now = utc(createdAt);
        jdbc.sql("""
                        INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, next_attempt_at, created_at)
                        VALUES (:id, :endToEndId, :messageId, 'original', :message, 'pending', :now, :now)
                        """)
                .param("id", id)
                .param("endToEndId", endToEndId)
                .param("messageId", messageId)
                .param("message", message)
                .param("now", now)
                .update();
    }

    /**
     * Takes up to {@code limit} due deliveries and moves their next attempt to {@code leaseUntil}, in one
     * statement. Rows another dispatcher holds are skipped, not waited for; a delivery whose sender dies
     * becomes due again when the lease ends.
     */
    List<Due> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE deliveries SET next_attempt_at = :leaseUntil
                        WHERE id IN (
                            SELECT id FROM deliveries
                            WHERE status = 'pending' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id, end_to_end_id, message_id, message, attempts, created_at
                        """)
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(DeliveryRepository::due)
                .list();
    }

    /** Records an attempt and the delivery's new state together. */
    @Transactional
    void recordAttempt(
            Due delivery,
            Instant attemptedAt,
            CoreClient.Response response,
            DeliveryOutcome outcome,
            @Nullable Instant nextAttemptAt) {
        int number = delivery.attempts() + 1;
        jdbc.sql("""
                        INSERT INTO delivery_attempts (delivery_id, number, attempted_at, response_status, error, outcome)
                        VALUES (:id, :number, :attemptedAt, :status, :error, :outcome)
                        """)
                .param("id", delivery.id())
                .param("number", number)
                .param("attemptedAt", utc(attemptedAt))
                .param("status", response.status())
                .param("error", response.error())
                .param("outcome", outcome.tag())
                .update();
        jdbc.sql("""
                        UPDATE deliveries SET status = :status, attempts = :number, next_attempt_at = :nextAttemptAt
                        WHERE id = :id
                        """)
                .param("id", delivery.id())
                .param("number", number)
                .param("status", outcome.status().name().toLowerCase(Locale.ROOT))
                .param("nextAttemptAt", nextAttemptAt == null ? null : utc(nextAttemptAt))
                .update();
    }

    private static Due due(ResultSet row, int rowNumber) throws SQLException {
        return new Due(
                row.getObject("id", UUID.class),
                row.getString("end_to_end_id"),
                row.getString("message_id"),
                row.getBytes("message"),
                row.getInt("attempts"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
