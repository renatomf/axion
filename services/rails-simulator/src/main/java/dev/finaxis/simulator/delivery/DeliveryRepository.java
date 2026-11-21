package dev.finaxis.simulator.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class DeliveryRepository {

    private final JdbcClient jdbc;

    DeliveryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID id, String endToEndId, String messageId, byte[] message, Instant createdAt) {
        var now = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
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
}
