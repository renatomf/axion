package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.webhooks.persistence.JdbcEndpoints;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the signing secrets whose grace period has ended (events and webhooks design, section
 * 6.2). An ended secret signs nothing; once removed, no copy of it is left, even encrypted.
 */
@Service
public class EndedSecretRemoval {

    static final int BATCH_SIZE = 1_000;

    private static final String ROLE = "axion_webhooks_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(EndedSecretRemoval.class);

    private final JdbcEndpoints endpoints;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EndedSecretRemoval(
            JdbcEndpoints endpoints, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.endpoints = endpoints;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be left.
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.MINUTES)
    public int removeEnded() {
        int removed = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactions.execute(
                    status -> roles.call(ROLE, () -> endpoints.deleteEndedSecrets(clock.instant(), BATCH_SIZE))));
            removed += batch;
        } while (batch == BATCH_SIZE);
        if (removed > 0) {
            LOG.info("Removed {} ended webhook signing secrets", removed);
        }
        return removed;
    }
}
