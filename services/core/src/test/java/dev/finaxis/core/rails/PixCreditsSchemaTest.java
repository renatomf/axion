package dev.finaxis.core.rails;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The inbox of Pix credits and the rules the database enforces on it (billing design, section 7.2),
 * checked against a real PostgreSQL as its owner; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PixCreditsSchemaTest {

    static final String END_TO_END_ID = "E99990011202611211430aB3dE5gH7jK";

    private static final Instant ACCEPTED = Instant.parse("2026-11-21T14:30:12.345Z");

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;

    @Autowired
    PixCreditsSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAReceivedCredit() {
        insert(Map.of());

        assertThat(jdbc.sql("SELECT status FROM rails.pix_credits WHERE end_to_end_id = ?")
                        .param(END_TO_END_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("received");
    }

    @Test
    void storesAHandledCredit() {
        insert(Map.of("status", "unmatched", "next_attempt_at", "", "processed_at", ACCEPTED.toString()));

        assertThat(jdbc.sql("SELECT status FROM rails.pix_credits WHERE end_to_end_id = ?")
                        .param(END_TO_END_ID)
                        .query(String.class)
                        .single())
                .isEqualTo("unmatched");
    }

    @Test
    void rejectsASecondCreditWithTheSameEndToEndId() {
        insert(Map.of());

        assertRejected(() -> insert(Map.of()), "pix_credits_pkey");
    }

    @Test
    void rejectsAMalformedEndToEndId() {
        assertRejected(
                () -> insert(Map.of("end_to_end_id", "X99990011202611211430aB3dE5gH7jK")),
                "pix_credits_end_to_end_id_check");
    }

    @Test
    void rejectsAMalformedMessageId() {
        assertRejected(
                () -> insert(Map.of("message_id", "M9999001aB3dE5gH7jK2mN4pQ6rS8tU!")), "pix_credits_message_id_check");
    }

    @Test
    void rejectsAMalformedTransactionId() {
        assertRejected(
                () -> insert(Map.of("transaction_id", "fx7Kq2mP9wR4tY6uA8sD3fG5h0")),
                "pix_credits_transaction_id_check");
    }

    @Test
    void rejectsAnAmountThatIsNotPositive() {
        assertRejected(() -> insert(Map.of("amount_minor", "0")), "pix_credits_amount_minor_check");
    }

    @Test
    void rejectsAnotherCurrency() {
        assertRejected(() -> insert(Map.of("currency", "USD")), "pix_credits_currency_check");
    }

    @Test
    void rejectsAMalformedDebtorIspb() {
        assertRejected(() -> insert(Map.of("debtor_ispb", "9999001A")), "pix_credits_debtor_ispb_check");
    }

    @Test
    void rejectsAKeyIdThatTheCoreCouldNotBeConfiguredWith() {
        assertRejected(() -> insert(Map.of("key_id", "rails 2026")), "pix_credits_key_id_check");
    }

    @Test
    void rejectsADigestThatIsNotSha256() {
        assertRejected(() -> insert(Map.of("content_digest", "abcd")), "pix_credits_content_digest_check");
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertRejected(
                () -> insert(Map.of("status", "failed", "next_attempt_at", "", "processed_at", ACCEPTED.toString())),
                "pix_credits_status_check");
    }

    @Test
    void rejectsAReceivedCreditThatIsNotDue() {
        assertRejected(() -> insert(Map.of("next_attempt_at", "")), "pix_credits_next_attempt_at_check");
    }

    @Test
    void rejectsAHandledCreditThatIsStillDue() {
        assertRejected(
                () -> insert(Map.of("status", "processed", "processed_at", ACCEPTED.toString())),
                "pix_credits_next_attempt_at_check");
    }

    @Test
    void rejectsAHandledCreditWithoutItsTime() {
        assertRejected(
                () -> insert(Map.of("status", "processed", "next_attempt_at", "")), "pix_credits_processed_at_check");
    }

    /**
     * Inserts a received credit, with the given columns changed; an empty value is {@code NULL}. The
     * digest is given in hex.
     */
    private void insert(Map<String, String> changes) {
        jdbc.sql("""
                        INSERT INTO rails.pix_credits (end_to_end_id, message_id, transaction_id, amount_minor,
                                                       currency, accepted_at, debtor_ispb, key_id, content_digest,
                                                       received_at, status, attempts, next_attempt_at, processed_at)
                        VALUES (:endToEndId, :messageId, :transactionId, CAST(:amount AS bigint), :currency,
                                :acceptedAt, :debtorIspb, :keyId, decode(:digest, 'hex'), :acceptedAt, :status, 0,
                                CAST(:nextAttemptAt AS timestamptz), CAST(:processedAt AS timestamptz))
                        """)
                .param("endToEndId", value(changes, "end_to_end_id", END_TO_END_ID))
                .param("messageId", value(changes, "message_id", "M99990011aB3dE5gH7jK2mN4pQ6rS8tU"))
                .param("transactionId", value(changes, "transaction_id", "fx7Kq2mP9wR4tY6uA8sD3fG5h"))
                .param("amount", value(changes, "amount_minor", "10000"))
                .param("currency", value(changes, "currency", "BRL"))
                .param("acceptedAt", ACCEPTED.atOffset(ZoneOffset.UTC))
                .param("debtorIspb", value(changes, "debtor_ispb", "99990011"))
                .param("keyId", value(changes, "key_id", "rails-2026-11"))
                .param("digest", value(changes, "content_digest", "ab".repeat(32)))
                .param("status", value(changes, "status", "received"))
                .param("nextAttemptAt", value(changes, "next_attempt_at", ACCEPTED.toString()))
                .param("processedAt", value(changes, "processed_at", ""))
                .update();
    }

    private static @Nullable String value(Map<String, String> changes, String column, String otherwise) {
        String value = changes.getOrDefault(column, otherwise);
        return value.isEmpty() ? null : value;
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
