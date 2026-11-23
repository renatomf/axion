package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.config.StalenessProperties;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publications whose listener failed, or whose process stopped, handed to the listener again as the
 * events and webhooks design, section 4.5, plans. A stopped process is played by a row left as it
 * would leave it. The clock stands still until a test moves it; the registry reads it too.
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
    private final InterruptedPublicationResubmission interrupted;
    private final EventPublicationRegistry registry;
    private final StalenessProperties staleness;
    private final MutableClock clock;
    private final JdbcClient owner;

    @Autowired
    RegistryRecoveryTest(
            ApplicationEventPublisher events,
            PlatformTransactionManager transactions,
            Probes probes,
            FailedPublicationResubmission resubmission,
            InterruptedPublicationResubmission interrupted,
            EventPublicationRegistry registry,
            StalenessProperties staleness,
            MutableClock clock,
            DatabaseOwner owner) {
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.probes = probes;
        this.resubmission = resubmission;
        this.interrupted = interrupted;
        this.registry = registry;
        this.staleness = staleness;
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

    @Test
    void handsBackOnStartWhatAStoppedProcessLeftRunning() {
        var probe = Probe.next();
        var published = clock.instant().minusSeconds(1);
        strand(probe, "PROCESSING", published, published, 1);

        interrupted.resubmitPublishedBefore(clock.instant());

        awaitRow(probe, new Row("COMPLETED", 2));
        assertThat(probes.handlings(probe)).hasSize(1);
    }

    @Test
    void leavesOnStartWhatThisProcessPublishedAndWhatIsDead() {
        var startedAt = clock.instant();
        var own = Probe.next();
        var dead = Probe.next();
        strand(own, "PROCESSING", startedAt, startedAt, 1);
        strand(dead, "FAILED", startedAt.minus(Duration.ofDays(1)), startedAt.minus(Duration.ofHours(2)), 10);

        interrupted.resubmitPublishedBefore(startedAt);

        assertThat(row(own)).isEqualTo(new Row("PROCESSING", 1));
        assertThat(row(dead)).isEqualTo(new Row("FAILED", 10));
    }

    @Test
    void failsAPublicationLostAfterItsResubmissionAndHandsItBack() {
        var probe = Probe.next();
        var resubmitted = clock.instant().minus(Duration.ofMinutes(11));
        strand(probe, "RESUBMITTED", resubmitted.minus(Duration.ofMinutes(1)), resubmitted, 2);

        registry.markStalePublicationsFailed(staleness);
        assertThat(row(probe)).isEqualTo(new Row("FAILED", 2));
        resubmission.resubmitDue();

        awaitRow(probe, new Row("COMPLETED", 3));
        assertThat(probes.handlings(probe)).hasSize(1);
    }

    private void publish(Probe probe) {
        transaction.executeWithoutResult(status -> events.publishEvent(probe));
    }

    private void awaitRow(Probe probe, Row expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(row(probe)).isEqualTo(expected));
    }

    /** Leaves a probe's publication in the registry as a stopped process would. */
    private void strand(Probe probe, String status, Instant published, Instant lastAttempt, int attempts) {
        owner.sql("""
                        INSERT INTO events.event_publication
                               (id, listener_id, event_type, serialized_event, publication_date, status,
                                completion_attempts, last_resubmission_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        listenerId(),
                        Probe.class.getName(),
                        "{\"id\":\"" + probe.id() + "\"}",
                        published.atOffset(ZoneOffset.UTC),
                        status,
                        attempts,
                        lastAttempt.atOffset(ZoneOffset.UTC))
                .update();
    }

    // The registry names a listener by its method; a probe published through it shows the name.
    private String listenerId() {
        var probe = Probe.next();
        publish(probe);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !probes.handlings(probe).isEmpty());
        return owner.sql("SELECT listener_id FROM events.event_publication WHERE serialized_event::jsonb ->> 'id' = ?")
                .param(probe.id().toString())
                .query(String.class)
                .single();
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
