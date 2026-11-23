package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.Probes.Probe;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The registry's gauges (events and webhooks design, section 12.3). The registry is shared with
 * every other test, so the counts are read as the change a test makes to them.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PublicationMetricsTest {

    private final MeterRegistry meters;
    private final JdbcClient owner;
    private final Clock clock;

    @Autowired
    PublicationMetricsTest(MeterRegistry meters, DatabaseOwner owner, Clock clock) {
        this.meters = meters;
        this.owner = owner.jdbc();
        this.clock = clock;
    }

    @Test
    void countsADeadPublicationAsFailedAndIncomplete() {
        double failed = gauge(PublicationMetrics.FAILED);
        double incomplete = gauge(PublicationMetrics.INCOMPLETE);

        insert("FAILED", 10, clock.instant().minus(Duration.ofDays(1)));

        assertThat(gauge(PublicationMetrics.FAILED)).isEqualTo(failed + 1);
        assertThat(gauge(PublicationMetrics.INCOMPLETE)).isEqualTo(incomplete + 1);
    }

    @Test
    void countsAPublicationStillRetriedAsIncompleteOnly() {
        double failed = gauge(PublicationMetrics.FAILED);
        double incomplete = gauge(PublicationMetrics.INCOMPLETE);

        insert("FAILED", 3, clock.instant());

        assertThat(gauge(PublicationMetrics.FAILED)).isEqualTo(failed);
        assertThat(gauge(PublicationMetrics.INCOMPLETE)).isEqualTo(incomplete + 1);
    }

    @Test
    void agesTheOldestPublicationStillRetriedButNoDeadOne() {
        var published = Instant.parse("2001-01-01T00:00:00Z");
        insert("FAILED", 10, published.minus(Duration.ofDays(365)));
        insert("FAILED", 2, published);

        double age = gauge(PublicationMetrics.OLDEST_INCOMPLETE);

        double sincePublished = Duration.between(published, clock.instant()).toMillis() / 1000.0;
        assertThat(age).isBetween(sincePublished, sincePublished + 60);
    }

    @Test
    void ageIsZeroWhenNothingWaitsAndNeverNegative() {
        var now = Instant.parse("2026-11-23T15:00:00Z");

        assertThat(PublicationMetrics.ageInSeconds(Optional.empty(), now)).isZero();
        assertThat(PublicationMetrics.ageInSeconds(Optional.of(now.plusSeconds(2)), now))
                .isZero();
        assertThat(PublicationMetrics.ageInSeconds(Optional.of(now.minusMillis(1_500)), now))
                .isEqualTo(1.5);
    }

    // A probe for a listener no context has: a test that resubmits it reads the probe, finds no
    // listener and leaves it failed.
    private void insert(String status, int attempts, Instant published) {
        var probe = Probe.next();
        owner.sql("""
                        INSERT INTO events.event_publication
                               (id, listener_id, event_type, serialized_event, publication_date, status,
                                completion_attempts)
                        VALUES (?, 'tests.metrics', ?, ?, ?, ?, ?)
                        """)
                .params(
                        UUID.randomUUID(),
                        Probe.class.getName(),
                        "{\"id\":\"" + probe.id() + "\"}",
                        published.atOffset(ZoneOffset.UTC),
                        status,
                        attempts)
                .update();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }
}
