package dev.axion.core.billing.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.persistence.JdbcCharges;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires the active charges whose expiry has passed, across tenants (billing design, section 9).
 * Whether a payment settles a charge never depends on it: settlement expires a due charge itself,
 * and judges lateness by when the rails accepted the payment.
 */
@Service
public class ChargeExpiry {

    static final int BATCH_SIZE = 500;

    private static final String ROLE = "axion_billing_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(ChargeExpiry.class);

    private final JdbcCharges charges;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ChargeExpiry(JdbcCharges charges, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.charges = charges;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be due.
    public int expireDue() {
        int expired = 0;
        int batch;
        do {
            var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            batch = Objects.requireNonNull(
                    transactions.execute(status -> roles.call(ROLE, () -> charges.expireDue(now, BATCH_SIZE))));
            expired += batch;
        } while (batch == BATCH_SIZE);
        if (expired > 0) {
            LOG.info("Expired {} charges", expired);
        }
        return expired;
    }
}
