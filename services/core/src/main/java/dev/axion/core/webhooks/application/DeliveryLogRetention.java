package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.webhooks.persistence.JdbcRetention;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Removes the delivery log once its retention ends (events and webhooks design, section 8.8):
 * deliveries 30 days after they were made and last attempted, with their attempts; then the events
 * no delivery sends any more; then the endpoints deleted 30 days ago whose log is gone, with their
 * secrets. The audit trail, not this log, is the permanent record.
 */
@Service
public class DeliveryLogRetention {

    static final int BATCH_SIZE = 1_000;

    private static final String ROLE = "axion_webhooks_jobs";

    private static final Logger LOG = LoggerFactory.getLogger(DeliveryLogRetention.class);

    private final JdbcRetention retention;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;

    public DeliveryLogRetention(JdbcRetention retention, ModuleRoles roles, TransactionTemplate transactions) {
        this.retention = retention;
        this.roles = roles;
        this.transactions = transactions;
    }

    /** What one run removed. */
    public record Removed(int deliveries, int events, int endpoints) {}

    // In this order, since each removal frees the rows of the next: an event or an endpoint goes only
    // once no delivery is left.
    @Scheduled(initialDelay = 5, fixedDelay = 60, timeUnit = TimeUnit.MINUTES)
    public Removed removeExpired() {
        var removed = new Removed(
                inBatches(retention::removeDeliveries),
                inBatches(retention::removeEvents),
                inBatches(retention::removeEndpoints));
        if (!removed.equals(new Removed(0, 0, 0))) {
            LOG.info(
                    "Removed {} webhook deliveries, {} events and {} deleted endpoints past their retention",
                    removed.deliveries(),
                    removed.events(),
                    removed.endpoints());
        }
        return removed;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be left.
    private int inBatches(IntUnaryOperator removal) {
        int removed = 0;
        int batch;
        do {
            batch = Objects.requireNonNull(
                    transactions.execute(status -> roles.call(ROLE, () -> removal.applyAsInt(BATCH_SIZE))));
            removed += batch;
        } while (batch == BATCH_SIZE);
        return removed;
    }
}
