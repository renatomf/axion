package dev.finaxis.simulator.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.finaxis.simulator.chaos.AppliedFault;
import dev.finaxis.simulator.chaos.Durations;
import dev.finaxis.simulator.chaos.Fault;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Repository
class DeliveryRepository {

    private static final TypeReference<List<StoredFault>> STORED_FAULTS = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final JsonMapper json;

    DeliveryRepository(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** A fault as the column holds it: the names of the operator API, and a duration in ISO 8601. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record StoredFault(
            String rule,
            String fault,
            @Nullable Integer attempt,
            @Nullable String waited) {

        static StoredFault of(AppliedFault applied) {
            Duration waited = applied.waited();
            return new StoredFault(
                    applied.rule().toString(),
                    applied.fault().tag(),
                    applied.attempt(),
                    waited == null ? null : Durations.inSeconds(waited));
        }

        AppliedFault applied() {
            Fault kind = Fault.ofTag(fault).orElseThrow(() -> new IllegalStateException("Unknown fault " + fault));
            return new AppliedFault(
                    UUID.fromString(rule), kind, attempt, waited == null ? null : Duration.parse(waited));
        }
    }

    /** A delivery the dispatcher holds a lease on, to send. A class, not a record: it holds the message's bytes. */
    static final class Due {

        private final UUID id;
        private final String endToEndId;
        private final String transactionId;
        private final String messageId;
        private final byte[] message;
        private final int attempts;
        private final Instant createdAt;

        Due(
                UUID id,
                String endToEndId,
                String transactionId,
                String messageId,
                byte[] message,
                int attempts,
                Instant createdAt) {
            this.id = id;
            this.endToEndId = endToEndId;
            this.transactionId = transactionId;
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

        /** The transaction ID of the payment's BR Code, which fault rules may match. */
        String transactionId() {
            return transactionId;
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

    /**
     * Records a delivery with the faults applied as it was created: pending, due at {@code nextAttemptAt}, or
     * held by a {@code reorder} rule when there is none.
     */
    void insert(
            UUID id,
            String endToEndId,
            String messageId,
            Kind kind,
            byte[] message,
            Instant createdAt,
            @Nullable Instant nextAttemptAt,
            List<AppliedFault> faults) {
        jdbc.sql("""
                        INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, next_attempt_at, faults, created_at)
                        VALUES (:id, :endToEndId, :messageId, :kind, :message, :status, :nextAttemptAt,
                                CAST(:faults AS jsonb), :createdAt)
                        """)
                .param("id", id)
                .param("endToEndId", endToEndId)
                .param("messageId", messageId)
                .param("kind", kind.tag())
                .param("message", message)
                .param("status", nextAttemptAt == null ? "held" : "pending")
                .param("nextAttemptAt", nextAttemptAt == null ? null : utc(nextAttemptAt))
                .param("faults", json(faults))
                .param("createdAt", utc(createdAt))
                .update();
    }

    /** The {@code reorder} rules that hold at least one delivery. */
    List<UUID> holdingRules() {
        return jdbc.sql("""
                        SELECT DISTINCT CAST(f ->> 'rule' AS uuid)
                        FROM deliveries d CROSS JOIN LATERAL jsonb_array_elements(d.faults) f
                        WHERE d.status = 'held' AND f ->> 'fault' = 'reorder'
                        """)
                .query((row, number) -> row.getObject(1, UUID.class))
                .list();
    }

    /**
     * Releases the deliveries a {@code reorder} rule holds: the newest becomes due at {@code now}, and each
     * older one {@code gap} after the one created after it. The copies of one message are released together.
     *
     * @return how many deliveries were released
     */
    int release(UUID rule, Instant now, Duration gap) {
        return jdbc.sql("""
                        UPDATE deliveries d
                        SET status = 'pending',
                            next_attempt_at = :now + (held.position - 1) * :gapMillis * interval '1 millisecond'
                        FROM (
                            SELECT id, dense_rank() OVER (ORDER BY created_at DESC, message_id) AS position
                            FROM deliveries
                            WHERE status = 'held' AND faults @> CAST(:hold AS jsonb)
                        ) held
                        WHERE d.id = held.id
                        """)
                .param("now", utc(now))
                .param("gapMillis", gap.toMillis())
                .param("hold", json.writeValueAsString(List.of(Map.of("rule", rule.toString(), "fault", "reorder"))))
                .update();
    }

    /**
     * Takes up to {@code limit} due deliveries and moves their next attempt to {@code leaseUntil}, in one
     * statement. Rows another dispatcher holds are skipped, not waited for; a delivery whose sender dies
     * becomes due again when the lease ends.
     */
    List<Due> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE deliveries d SET next_attempt_at = :leaseUntil
                        FROM payments p
                        WHERE p.end_to_end_id = d.end_to_end_id AND d.id IN (
                            SELECT id FROM deliveries
                            WHERE status = 'pending' AND next_attempt_at <= :now
                            ORDER BY next_attempt_at
                            LIMIT :limit
                            FOR UPDATE SKIP LOCKED)
                        RETURNING d.id, d.end_to_end_id, p.transaction_id, d.message_id, d.message, d.attempts, d.created_at
                        """)
                .param("now", utc(now))
                .param("leaseUntil", utc(leaseUntil))
                .param("limit", limit)
                .query(DeliveryRepository::due)
                .list();
    }

    /** Records an attempt, the faults applied to it and the delivery's new state together. */
    @Transactional
    void recordAttempt(
            Due delivery,
            Instant attemptedAt,
            CoreClient.Response response,
            DeliveryOutcome outcome,
            @Nullable Instant nextAttemptAt,
            List<AppliedFault> faults) {
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
                        UPDATE deliveries
                        SET status = :status, attempts = :number, next_attempt_at = :nextAttemptAt,
                            faults = faults || CAST(:faults AS jsonb)
                        WHERE id = :id
                        """)
                .param("id", delivery.id())
                .param("number", number)
                .param("faults", json(faults))
                .param("status", outcome.status().name().toLowerCase(Locale.ROOT))
                .param("nextAttemptAt", nextAttemptAt == null ? null : utc(nextAttemptAt))
                .update();
    }

    long countPending() {
        return jdbc.sql("SELECT count(*) FROM deliveries WHERE status = 'pending'")
                .query((row, number) -> row.getLong(1))
                .single();
    }

    List<Delivery> findByEndToEndId(String endToEndId) {
        Map<UUID, List<Delivery.Attempt>> attempts = jdbc
                .sql("""
                        SELECT a.delivery_id, a.number, a.attempted_at, a.response_status, a.error, a.outcome
                        FROM delivery_attempts a JOIN deliveries d ON d.id = a.delivery_id
                        WHERE d.end_to_end_id = ?
                        ORDER BY a.number
                        """)
                .param(endToEndId)
                .query((row, number) -> Map.entry(row.getObject("delivery_id", UUID.class), attempt(row)))
                .list()
                .stream()
                .collect(Collectors.groupingBy(
                        Map.Entry::getKey, Collectors.mapping(Map.Entry::getValue, Collectors.toList())));
        return jdbc.sql("""
                        SELECT id, kind, status, created_at, next_attempt_at, faults
                        FROM deliveries
                        WHERE end_to_end_id = ?
                        ORDER BY created_at, kind <> 'original', id
                        """)
                .param(endToEndId)
                .query((row, number) -> {
                    UUID id = row.getObject("id", UUID.class);
                    OffsetDateTime next = row.getObject("next_attempt_at", OffsetDateTime.class);
                    return new Delivery(
                            id,
                            row.getString("kind"),
                            DeliveryStatus.valueOf(row.getString("status").toUpperCase(Locale.ROOT)),
                            row.getObject("created_at", OffsetDateTime.class).toInstant(),
                            next == null ? null : next.toInstant(),
                            attempts.getOrDefault(id, List.of()),
                            json.readValue(row.getString("faults"), STORED_FAULTS).stream()
                                    .map(StoredFault::applied)
                                    .toList());
                })
                .list();
    }

    private static Delivery.Attempt attempt(ResultSet row) throws SQLException {
        return new Delivery.Attempt(
                row.getInt("number"),
                row.getObject("attempted_at", OffsetDateTime.class).toInstant(),
                row.getObject("response_status", Integer.class),
                row.getString("error"),
                row.getString("outcome"));
    }

    private static Due due(ResultSet row, int rowNumber) throws SQLException {
        return new Due(
                row.getObject("id", UUID.class),
                row.getString("end_to_end_id"),
                row.getString("transaction_id"),
                row.getString("message_id"),
                row.getBytes("message"),
                row.getInt("attempts"),
                row.getObject("created_at", OffsetDateTime.class).toInstant());
    }

    private String json(List<AppliedFault> faults) {
        return json.writeValueAsString(faults.stream().map(StoredFault::of).toList());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
