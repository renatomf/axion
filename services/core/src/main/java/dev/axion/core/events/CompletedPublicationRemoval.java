package dev.axion.core.events;

import dev.axion.core.ModuleRoles;
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
 * Removes the publications completed more than 7 days ago (events and webhooks design, section 4.2).
 * Incomplete publications are never removed, however old: they are owed to a listener.
 */
@Service
class CompletedPublicationRemoval {

    static final Duration RETENTION = Duration.ofDays(7);
    static final int BATCH_SIZE = 1_000;

    private static final String ROLE = "axion_events";

    private static final Logger LOG = LoggerFactory.getLogger(CompletedPublicationRemoval.class);

    private final JdbcPublications publications;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    CompletedPublicationRemoval(
            JdbcPublications publications, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.publications = publications;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be left.
    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.HOURS)
    int removeCompleted() {
        var cutoff = clock.instant().minus(RETENTION);
        int removed = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(transactions.execute(
                    status -> roles.call(ROLE, () -> publications.deleteCompletedBefore(cutoff, BATCH_SIZE))));
            removed += batch;
        } while (batch == BATCH_SIZE);
        if (removed > 0) {
            LOG.info("Removed {} completed event publications", removed);
        }
        return removed;
    }
}
