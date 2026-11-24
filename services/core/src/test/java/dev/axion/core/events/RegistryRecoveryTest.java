package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * would leave it, and an operator by the command of V44. The clock stands still until a test moves
 * it; the registry reads it too.
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
    private final JdbcClient jdbc;

    private @Nullable String listenerId;

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
            DatabaseOwner owner,
            JdbcClient jdbc) {
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
        this.probes = probes;
        this.resubmission = resubmission;
        this.interrupted = interrupted;
        this.registry = registry;
        this.staleness = staleness;
        this.clock = clock;
        this.owner = owner.jdbc();
        this.jdbc = jdbc;
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

    @ParameterizedTest
    @ValueSource(strings = {"PUBLISHED", "PROCESSING", "RESUBMITTED"})
    void handsBackOnStartWhatAStoppedProcessLeftIncomplete(String status) {
        var probe = Probe.next();
        var published = clock.instant().minusSeconds(1);
        strand(probe, status, published, published, 1);

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

    @Test
    void handsADeadPublicationBackOnAnOperatorsCommand() {
        var probe = Probe.next();
        var now = clock.instant();
        UUID dead = strand(probe, "FAILED", now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(2)), 10);

        assertThat(resubmitAsOperator(dead, listenerId())).isTrue();
        assertThat(row(probe)).isEqualTo(new Row("FAILED", 1));
        resubmission.resubmitDue();

        awaitRow(probe, new Row("COMPLETED", 2));
        assertThat(probes.handlings(probe)).hasSize(1);
    }

    @Test
    void refusesTheOperatorsCommandForAPublicationNotDeadOrOfAnotherListener() {
        var now = clock.instant();
        var retried = Probe.next();
        var dead = Probe.next();
        UUID stillRetried =
                strand(retried, "FAILED", now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(30)), 3);
        UUID deadOne = strand(dead, "FAILED", now.minus(Duration.ofDays(1)), now.minus(Duration.ofHours(2)), 10);

        assertThat(resubmitAsOperator(stillRetried, listenerId())).isFalse();
        assertThat(resubmitAsOperator(deadOne, "another.listener(java.lang.Object)"))
                .isFalse();
        assertThat(resubmitAsOperator(UUID.randomUUID(), listenerId())).isFalse();

        assertThat(row(retried)).isEqualTo(new Row("FAILED", 3));
        assertThat(row(dead)).isEqualTo(new Row("FAILED", 10));
    }

    private boolean resubmitAsOperator(UUID publication, String listener) {
        return Boolean.TRUE.equals(transaction.execute(status -> {
            DatabaseRole.EVENTS.actAs(jdbc);
            return jdbc.sql("SELECT events.resubmit_dead_publication(?, ?)")
                    .params(publication, listener)
                    .query(Boolean.class)
                    .single();
        }));
    }

    private void publish(Probe probe) {
        transaction.executeWithoutResult(status -> events.publishEvent(probe));
    }

    private void awaitRow(Probe probe, Row expected) {
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(row(probe)).isEqualTo(expected));
    }

    /** Leaves a probe's publication in the registry as a stopped process would. */
    private UUID strand(Probe probe, String status, Instant published, Instant lastAttempt, int attempts) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO events.event_publication
                               (id, listener_id, event_type, serialized_event, publication_date, status,
                                completion_attempts, last_resubmission_date)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        id,
                        listenerId(),
                        Probe.class.getName(),
                        "{\"id\":\"" + probe.id() + "\"}",
                        published.atOffset(ZoneOffset.UTC),
                        status,
                        attempts,
                        lastAttempt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    // The registry names a listener by its method; a probe published through it shows the name.
    private String listenerId() {
        if (listenerId != null) {
            return listenerId;
        }
        var probe = Probe.next();
        publish(probe);
        await().atMost(Duration.ofSeconds(5))
                .until(() -> !probes.handlings(probe).isEmpty());
        listenerId = owner.sql(
                        "SELECT listener_id FROM events.event_publication WHERE serialized_event::jsonb ->> 'id' = ?")
                .param(probe.id().toString())
                .query(String.class)
                .single();
        return listenerId;
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
