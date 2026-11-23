package dev.axion.core.risk.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.risk.persistence.JdbcChargeVolume;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the risk windows older than 7 days (billing design, section 6). No rule reads a window
 * past its hour or its business date, so they are kept only to explain recent denials.
 */
@Service
public class OldWindowRemoval {

    static final Duration RETENTION = Duration.ofDays(7);

    static final int BATCH_SIZE = 1_000;

    private static final String ROLE = "finaxis_risk_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(OldWindowRemoval.class);

    private final JdbcChargeVolume volume;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public OldWindowRemoval(JdbcChargeVolume volume, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.volume = volume;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be left.
    @Scheduled(initialDelay = 1, fixedDelay = 24, timeUnit = TimeUnit.HOURS)
    public int removeOld() {
        int removed = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactions.execute(status -> roles.call(
                    ROLE, () -> volume.deleteStartedBefore(clock.instant().minus(RETENTION), BATCH_SIZE))));
            removed += batch;
        } while (batch == BATCH_SIZE);
        if (removed > 0) {
            LOG.info("Removed {} risk windows older than {} days", removed, RETENTION.toDays());
        }
        return removed;
    }
}
