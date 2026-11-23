package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One effect per event and consumer (events and webhooks design, section 4.4), against a {@code
 * consumed_events} table of the tests, as a consuming module's migration would create it.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IdempotentConsumerTest {

    private static final String TABLE = "consumer_tests.consumed_events";

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final DatabaseOwner owner;
    private final IdempotentConsumer consumer;

    @Autowired
    IdempotentConsumerTest(JdbcClient jdbc, PlatformTransactionManager transactions, DatabaseOwner owner, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.owner = owner;
        this.consumer = new IdempotentConsumer(jdbc, clock, TABLE, "tests.counter");
    }

    @BeforeEach
    void createTheTable() {
        owner.jdbc().sql("CREATE SCHEMA IF NOT EXISTS consumer_tests").update();
        owner.jdbc().sql("""
                        CREATE TABLE IF NOT EXISTS consumer_tests.consumed_events (
                            consumer     text        NOT NULL,
                            event_id     uuid        NOT NULL,
                            consumed_at  timestamptz NOT NULL,
                            PRIMARY KEY (consumer, event_id)
                        )
                        """).update();
        owner.jdbc().sql("GRANT USAGE ON SCHEMA consumer_tests TO axion_app").update();
        owner.jdbc()
                .sql("GRANT SELECT, INSERT ON consumer_tests.consumed_events TO axion_app")
                .update();
    }

    @Test
    void runsTheEffectOfAnEventOnce() {
        var event = nextEvent();
        var effects = new AtomicInteger();

        boolean first = handleCommitted(consumer, event, effects::incrementAndGet);
        boolean again = handleCommitted(consumer, event, effects::incrementAndGet);

        assertThat(first).isTrue();
        assertThat(again).isFalse();
        assertThat(effects).hasValue(1);
        assertThat(records(event)).isEqualTo(1);
    }

    @Test
    void letsEachConsumerHandleTheEvent(@Autowired Clock clock) {
        var event = nextEvent();
        var other = new IdempotentConsumer(jdbc, clock, TABLE, "tests.other");

        assertThat(handleCommitted(consumer, event, () -> {})).isTrue();
        assertThat(handleCommitted(other, event, () -> {})).isTrue();
        assertThat(records(event)).isEqualTo(2);
    }

    @Test
    void handlesAgainAnEventWhoseEffectRolledBack() {
        var event = nextEvent();
        var effects = new AtomicInteger();

        assertThatThrownBy(() -> handleCommitted(consumer, event, () -> {
                    throw new IllegalStateException("The effect fails");
                }))
                .hasMessage("The effect fails");
        boolean retried = handleCommitted(consumer, event, effects::incrementAndGet);

        assertThat(retried).isTrue();
        assertThat(effects).hasValue(1);
    }

    @Test
    void runsNothingForADuplicateThatArrivesWhileTheFirstIsRunning() throws Exception {
        var event = nextEvent();
        var effects = new AtomicInteger();
        var firstRunning = new CountDownLatch(1);
        var firstMayCommit = new CountDownLatch(1);

        var first = CompletableFuture.supplyAsync(() -> handleCommitted(consumer, event, () -> {
            effects.incrementAndGet();
            firstRunning.countDown();
            await(firstMayCommit);
        }));
        await(firstRunning);
        var duplicate = CompletableFuture.supplyAsync(() -> handleCommitted(consumer, event, effects::incrementAndGet));
        // The duplicate's insert waits on the first transaction's key until that commits.
        Thread.sleep(Duration.ofMillis(200));
        assertThat(duplicate).isNotDone();
        firstMayCommit.countDown();

        assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(duplicate.get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(effects).hasValue(1);
    }

    @Test
    void refusesToHandleOutsideATransaction() {
        assertThatThrownBy(() -> consumer.handle(nextEvent(), () -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("An event is handled in the transaction of its effect");
    }

    @Test
    void refusesATableThatIsNotAConsumedEventsTable(@Autowired Clock clock) {
        assertThatThrownBy(() -> new IdempotentConsumer(jdbc, clock, "billing.charges", "tests.counter"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean handleCommitted(IdempotentConsumer handler, EventId event, Runnable effect) {
        return Boolean.TRUE.equals(transaction.execute(status -> handler.handle(event, effect)));
    }

    private long records(EventId event) {
        return owner.jdbc()
                .sql("SELECT count(*) FROM consumer_tests.consumed_events WHERE event_id = ?")
                .param(event.value())
                .query(Long.class)
                .single();
    }

    private static EventId nextEvent() {
        return new EventId(UUID.randomUUID());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Waited too long");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
