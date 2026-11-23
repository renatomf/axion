package dev.axion.core.shared;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Gives a consumer one effect per event, however often the event is delivered to it (events and
 * webhooks design, section 4.4). The event is recorded in the consuming module's {@code
 * consumed_events} table in the transaction of the effect, so the two commit or roll back together.
 */
public final class IdempotentConsumer {

    private static final Pattern TABLE = Pattern.compile("[a-z_]+\\.consumed_events");

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String consumer;
    private final String insert;

    /**
     * @param table the module's table, such as {@code audit.consumed_events}
     * @param consumer the name the consumer's records carry, unique within the table
     */
    public IdempotentConsumer(JdbcClient jdbc, Clock clock, String table, String consumer) {
        if (!TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("Not a consumed_events table: " + table);
        }
        if (consumer.isBlank()) {
            throw new IllegalArgumentException("A consumer needs a name");
        }
        this.jdbc = jdbc;
        this.clock = clock;
        this.consumer = consumer;
        this.insert = """
                INSERT INTO %s (consumer, event_id, consumed_at) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING
                """.formatted(table);
    }

    /**
     * Runs the effect unless this consumer has handled the event already. While another transaction
     * handles the same event, the record waits for it: if it commits, this one runs nothing.
     *
     * @return whether the effect ran
     * @throws IllegalStateException outside a transaction, where the record and the effect could part
     */
    public boolean handle(EventId event, Runnable effect) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An event is handled in the transaction of its effect");
        }
        int recorded = jdbc.sql(insert)
                .params(consumer, event.value(), clock.instant().atOffset(ZoneOffset.UTC))
                .update();
        if (recorded == 0) {
            return false;
        }
        effect.run();
        return true;
    }
}
