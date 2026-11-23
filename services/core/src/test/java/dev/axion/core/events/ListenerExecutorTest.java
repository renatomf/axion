package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The executor listeners run on (events and webhooks design, section 4.3). */
@SpringBootTest
@Import({PostgresTestConfiguration.class, Probes.class})
class ListenerExecutorTest {

    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Probes probes;

    @Autowired
    ListenerExecutorTest(ApplicationEventPublisher events, PlatformTransactionManager transactions, Probes probes) {
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.probes = probes;
    }

    @Test
    void runsListenersOnVirtualThreadsOfItsOwnAfterTheCommit() {
        var probe = Probe.next();
        String committer = Thread.currentThread().getName();

        transaction.executeWithoutResult(status -> events.publishEvent(probe));

        await().atMost(Duration.ofSeconds(5))
                .until(() -> !probes.handlings(probe).isEmpty());
        var handling = probes.handlings(probe).getFirst();
        assertThat(handling.virtual()).isTrue();
        assertThat(handling.thread()).startsWith("event-listener-").isNotEqualTo(committer);
    }

    @Test
    void runsThirtyTwoListenersAtATime(@Autowired AsyncConfigurer listeners) {
        var executor = (ThreadPoolTaskExecutor) Objects.requireNonNull(listeners.getAsyncExecutor());

        assertThat(executor.getCorePoolSize()).isEqualTo(32);
        assertThat(executor.getMaxPoolSize()).isEqualTo(32);
    }
}
