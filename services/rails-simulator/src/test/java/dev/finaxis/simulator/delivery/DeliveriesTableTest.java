package dev.finaxis.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.simulator.PostgresTestConfiguration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The constraints of the deliveries table (rails simulator design, section 8.3), as the simulator's role sees them. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DeliveriesTableTest {

    private static final String END_TO_END_ID = "E99990011202611211430dddddddddd1";
    private static final String MESSAGE_ID = "M99990011aB3dE5gH7jK2mN4pQ6rS8tU";
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 21, 14, 30, 12, 0, ZoneOffset.UTC);

    private final JdbcClient jdbc;

    @Autowired
    DeliveriesTableTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @BeforeEach
    void payment() {
        jdbc.sql("""
                        INSERT INTO payments (end_to_end_id, transaction_id, amount_minor, debtor_ispb, br_code, settled_at)
                        VALUES (?, 'tx', 100, '99990011', '000201', ?)
                        ON CONFLICT DO NOTHING
                        """).params(END_TO_END_ID, NOW).update();
    }

    @Test
    void acceptsAPendingDelivery() {
        insert(END_TO_END_ID, MESSAGE_ID, "original", "pending", NOW);

        assertThat(jdbc.sql("SELECT count(*) FROM deliveries WHERE end_to_end_id = ?")
                        .param(END_TO_END_ID)
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    @Test
    void refusesADeliveryOfAnUnknownPayment() {
        assertThatThrownBy(() -> insert("E99990011202611211430dddddddddd9", MESSAGE_ID, "original", "pending", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "X99990011aB3dE5gH7jK2mN4pQ6rS8tU", // not starting with M
                "M9999001AaB3dE5gH7jK2mN4pQ6rS8tU", // ISPB with a letter
                "M99990011aB3dE5gH7jK2mN4pQ6rS8t-" // a character that is neither a letter nor a digit
            })
    void refusesAMalformedMessageId(String messageId) {
        assertThatThrownBy(() -> insert(END_TO_END_ID, messageId, "original", "pending", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnUnknownKind() {
        assertThatThrownBy(() -> insert(END_TO_END_ID, MESSAGE_ID, "copy", "pending", NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAnUnknownStatus() {
        assertThatThrownBy(() -> insert(END_TO_END_ID, MESSAGE_ID, "original", "sent", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesAPendingDeliveryWithoutANextAttempt() {
        assertThatThrownBy(() -> insert(END_TO_END_ID, MESSAGE_ID, "original", "pending", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void acceptsAHeldDeliveryWithoutANextAttempt() {
        UUID id = insert(END_TO_END_ID, MESSAGE_ID, "original", "held", null);

        assertThat(jdbc.sql("SELECT status FROM deliveries WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("held");
    }

    @ParameterizedTest
    @ValueSource(strings = {"held", "delivered", "refused", "failed"})
    void refusesADeliveryNotPendingWithANextAttempt(String status) {
        assertThatThrownBy(() -> insert(END_TO_END_ID, MESSAGE_ID, "original", status, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void refusesANegativeAttemptCount() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, attempts, next_attempt_at, created_at)
                                VALUES (?, ?, ?, 'original', '\\x7b7d', 'pending', -1, ?, ?)
                                """)
                        .params(UUID.randomUUID(), END_TO_END_ID, MESSAGE_ID, NOW, NOW)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void startsWithNoFaults() {
        UUID id = insert(END_TO_END_ID, MESSAGE_ID, "original", "pending", NOW);

        assertThat(jdbc.sql("SELECT faults::text FROM deliveries WHERE id = ?")
                        .param(id)
                        .query(String.class)
                        .single())
                .isEqualTo("[]");
    }

    @Test
    void refusesFaultsThatAreNotAList() {
        UUID id = insert(END_TO_END_ID, MESSAGE_ID, "original", "pending", NOW);

        assertThatThrownBy(() -> jdbc.sql("UPDATE deliveries SET faults = '{}' WHERE id = ?")
                        .param(id)
                        .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID insert(
            String endToEndId, String messageId, String kind, String status, @Nullable OffsetDateTime nextAttemptAt) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, next_attempt_at, created_at)
                        VALUES (:id, :endToEndId, :messageId, :kind, '\\x7b7d', :status, :nextAttemptAt, :createdAt)
                        """)
                .param("id", id)
                .param("endToEndId", endToEndId)
                .param("messageId", messageId)
                .param("kind", kind)
                .param("status", status)
                .param("nextAttemptAt", nextAttemptAt)
                .param("createdAt", NOW)
                .update();
        return id;
    }
}
