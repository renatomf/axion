package dev.finaxis.core.rails.persistence;

import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.shared.Money;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPixCredits {

    private final JdbcClient jdbc;

    public JdbcPixCredits(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the credit, due at once, unless a credit with its {@code EndToEndId} is already
     * recorded; answers whether it did. Two copies arriving together are decided by the primary
     * key: one inserts, the other waits for it and skips.
     */
    public boolean insertIfAbsent(PixCredit credit, String keyId, byte[] contentDigest, Instant receivedAt) {
        return jdbc.sql("""
                        INSERT INTO rails.pix_credits (end_to_end_id, message_id, transaction_id, amount_minor,
                                                       currency, accepted_at, debtor_ispb, key_id, content_digest,
                                                       received_at, status, attempts, next_attempt_at)
                        VALUES (:endToEndId, :messageId, :transactionId, :amount, :currency, :acceptedAt,
                                :debtorIspb, :keyId, :digest, :receivedAt, 'received', 0, :receivedAt)
                        ON CONFLICT (end_to_end_id) DO NOTHING
                        """)
                        .param("endToEndId", credit.endToEndId())
                        .param("messageId", credit.messageId())
                        .param("transactionId", credit.transactionId())
                        .param("amount", credit.amount().minorUnits())
                        .param("currency", credit.amount().currency().getCurrencyCode())
                        .param("acceptedAt", credit.acceptedAt().atOffset(ZoneOffset.UTC))
                        .param("debtorIspb", credit.debtorIspb())
                        .param("keyId", keyId)
                        .param("digest", contentDigest)
                        .param("receivedAt", receivedAt.atOffset(ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /**
     * Claims up to {@code limit} received credits that are due, oldest due first, and leases them
     * until {@code leaseUntil}: another processor skips them while they are locked and does not find
     * them due until the lease ends. Committed with the caller's transaction.
     */
    public List<PendingCredit> claimDue(Instant now, Instant leaseUntil, int limit) {
        return jdbc.sql("""
                        UPDATE rails.pix_credits
                           SET next_attempt_at = :leaseUntil
                         WHERE end_to_end_id IN (SELECT end_to_end_id
                                                   FROM rails.pix_credits
                                                  WHERE status = 'received' AND next_attempt_at <= :now
                                                  ORDER BY next_attempt_at
                                                  LIMIT :limit
                                                    FOR UPDATE SKIP LOCKED)
                        RETURNING end_to_end_id, message_id, transaction_id, amount_minor, currency, accepted_at,
                                  debtor_ispb, attempts
                        """)
                .param("now", now.atOffset(ZoneOffset.UTC))
                .param("leaseUntil", leaseUntil.atOffset(ZoneOffset.UTC))
                .param("limit", limit)
                .query((row, rowNumber) -> new PendingCredit(
                        new PixCredit(
                                row.getString("end_to_end_id"),
                                row.getString("message_id"),
                                row.getString("transaction_id"),
                                Money.ofMinor(
                                        row.getLong("amount_minor"), Currency.getInstance(row.getString("currency"))),
                                row.getObject("accepted_at", OffsetDateTime.class)
                                        .toInstant(),
                                row.getString("debtor_ispb")),
                        row.getInt("attempts")))
                .list();
    }

    /**
     * Records that billing handled the credit, as {@code processed} or {@code unmatched}; it is no
     * longer due. A credit handled already is left as it is.
     */
    public void markHandled(String endToEndId, Handled status, Instant handledAt) {
        jdbc.sql("""
                        UPDATE rails.pix_credits
                           SET status = :status, attempts = attempts + 1, next_attempt_at = NULL,
                               processed_at = :handledAt
                         WHERE end_to_end_id = :endToEndId AND status = 'received'
                        """)
                .param("status", status.column)
                .param("handledAt", handledAt.atOffset(ZoneOffset.UTC))
                .param("endToEndId", endToEndId)
                .update();
    }

    /** Records a failed attempt; the credit is due again at {@code nextAttemptAt}. */
    public void markFailed(String endToEndId, Instant nextAttemptAt) {
        jdbc.sql("""
                        UPDATE rails.pix_credits
                           SET attempts = attempts + 1, next_attempt_at = :nextAttemptAt
                         WHERE end_to_end_id = :endToEndId AND status = 'received'
                        """)
                .param("nextAttemptAt", nextAttemptAt.atOffset(ZoneOffset.UTC))
                .param("endToEndId", endToEndId)
                .update();
    }

    /** When the oldest credit still to hand to billing was received; empty if none is. */
    public Optional<Instant> oldestPendingReceivedAt() {
        return jdbc.sql("SELECT min(received_at) AS oldest FROM rails.pix_credits WHERE status = 'received'")
                .query((row, rowNumber) -> Optional.ofNullable(row.getObject("oldest", OffsetDateTime.class)))
                .single()
                .map(OffsetDateTime::toInstant);
    }

    /** What billing did with a credit, as the inbox records it. */
    public enum Handled {
        PROCESSED("processed"),
        UNMATCHED("unmatched");

        private final String column;

        Handled(String column) {
            this.column = column;
        }
    }

    /** A claimed credit, with the attempts made to hand it over before this one. */
    public record PendingCredit(PixCredit credit, int attempts) {}
}
