package dev.finaxis.core.rails.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.rails.domain.ReceivedCredit;
import dev.finaxis.core.rails.persistence.JdbcPixCredits;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records each verified Pix credit once, by its {@code EndToEndId} (billing design, section 7.2),
 * before the rails are answered. A duplicated notification is accepted and changes nothing: the
 * first of the three barriers against a double credit.
 */
@Service
public class CreditInbox {

    /** Whether the credit was recorded now, or had been already. */
    public enum Reception {
        NEW,
        DUPLICATE
    }

    private static final String ROLE = "finaxis_rails";

    private static final Logger LOG = LoggerFactory.getLogger(CreditInbox.class);

    private final JdbcPixCredits credits;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public CreditInbox(JdbcPixCredits credits, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.credits = credits;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Records the credit with the evidence of what was accepted: the key that verified its signature
     * and the SHA-256 of the exact bytes. Committed when this returns.
     */
    public Reception record(ReceivedCredit credit, String keyId, byte[] contentDigest) {
        boolean inserted = Objects.requireNonNull(transactions.execute(status ->
                roles.call(ROLE, () -> credits.insertIfAbsent(credit, keyId, contentDigest, clock.instant()))));
        if (inserted) {
            LOG.info("Recorded Pix credit {} of message {}", credit.endToEndId(), credit.messageId());
            return Reception.NEW;
        }
        LOG.info("Pix credit {} of message {} was already recorded", credit.endToEndId(), credit.messageId());
        return Reception.DUPLICATE;
    }
}
