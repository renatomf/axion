package dev.axion.core.idempotency.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.idempotency.persistence.JdbcIdempotencyKeys;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the idempotency keys past their retention (idempotency design, section 6.2). Once its row
 * is gone, a key is unknown, and a request with it runs as new.
 */
@Service
public class ExpiredKeyRemoval {

    static final int BATCH_SIZE = 1_000;

    private static final String ROLE = "axion_idempotency_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(ExpiredKeyRemoval.class);

    private final JdbcIdempotencyKeys keys;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ExpiredKeyRemoval(
            JdbcIdempotencyKeys keys, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.keys = keys;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be left.
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    public int removeExpired() {
        int removed = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactions.execute(
                    status -> roles.call(ROLE, () -> keys.deleteExpired(clock.instant(), BATCH_SIZE))));
            removed += batch;
        } while (batch == BATCH_SIZE);
        if (removed > 0) {
            LOG.info("Removed {} expired idempotency keys", removed);
        }
        return removed;
    }
}
