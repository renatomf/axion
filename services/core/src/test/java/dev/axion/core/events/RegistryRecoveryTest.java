package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publications whose listener failed, handed to it again on the schedule of the events and webhooks
 * design, section 4.5. The clock stands still until a test moves it; the registry reads it too.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, Probes.class, RegistryRecoveryTest.Configuration.class})
class RegistryRecoveryTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-23T15:00:00Z"));
        }
    }

    /** A probe's row in the registry. */
    record Row(String status, int attempts) {}

    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;
    private final Probes probes;
    private final FailedPublicationResubmission resubmission;
    private final MutableClock clock;
    private final JdbcClient owner;

    @Autowired
    RegistryRecoveryTest(
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions,
            Probes probes,
            FailedPublicationResubmission resubmission,
            MutableClock clock,
            DatabaseOwner owner) {
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.probes = probes;
        this.resubmission = resubmission;
        this.clock = clock;
        this.owner = owner.jdbc();
    }

    @Test
    void handsAFailedPublicationBackWithBackoffUntilItsListenerSucceeds() {
        var probe = Probe.next();
        probes.failNext(probe, 2);

        publish(probe);
        awaitRow(probe, new Row("FAILED", 1));

        resubmission.resubmitDue();
        assertThat(row(probe)).isEqualTo(new Row("FAILED", 1));

        clock.advance(Duration.ofMinutes(1));
        resubmission.resubmitDue();
        awaitRow(probe, new Row("FAILED", 2));

        clock.advance(Duration.ofMinutes(4));
        resubmission.resubmitDue();
        assertThat(row(probe)).isEqualTo(new Row("FAILED", 2));

        clock.advance(Duration.ofMinutes(1));
        resubmission.resubmitDue();
        awaitRow(probe, new Row("COMPLETED", 3));
        assertThat(probes.handlings(probe)).hasSize(1);
    }

    @Test
    void leavesADeadPublicationAlone() {
        var probe = Probe.next();
        probes.failNext(probe, Integer.MAX_VALUE);
        publish(probe);
        awaitRow(probe, new Row("FAILED", 1));
        owner.sql("""
                        UPDATE events.event_publication SET completion_attempts = 10
                         WHERE serialized_event::jsonb ->> 'id' = ?
                        """).param(probe.id().toString()).update();

        clock.advance(Duration.ofDays(1));
        resubmission.resubmitDue();

        assertThat(row(probe)).isEqualTo(new Row("FAILED", 10));
    }

    private void publish(Probe probe) {
        transaction.executeWithoutResult(status -> events.publishEvent(probe));
    }

    private void awaitRow(Probe probe, Row expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(row(probe)).isEqualTo(expected));
    }

    private Row row(Probe probe) {
        return owner.sql("""
                        SELECT status, completion_attempts FROM events.event_publication
                         WHERE serialized_event::jsonb ->> 'id' = ?
                        """)
                .param(probe.id().toString())
                .query((result, number) -> new Row(result.getString("status"), result.getInt("completion_attempts")))
                .single();
    }
}
