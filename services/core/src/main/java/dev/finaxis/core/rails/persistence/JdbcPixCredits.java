package dev.finaxis.core.rails.persistence;

import dev.finaxis.core.rails.domain.ReceivedCredit;
import java.time.Instant;
import java.time.ZoneOffset;
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
    public boolean insertIfAbsent(ReceivedCredit credit, String keyId, byte[] contentDigest, Instant receivedAt) {
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
}
