package dev.axion.core.events;

import dev.axion.core.ModuleRoles;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The registry's health (events and webhooks design, section 12.3), read from it whenever the gauges
 * are read: the incomplete publications, how long the oldest one still being retried has waited,
 * and the dead ones, which an operator must resubmit. Any dead publication is an alert.
 */
@Component
class PublicationMetrics {

    static final String INCOMPLETE = "axion.events.publications.incomplete";
    static final String OLDEST_INCOMPLETE = "axion.events.publication.oldest.incomplete";
    static final String FAILED = "axion.events.publications.failed";

    private static final String ROLE = "axion_events";

    private final JdbcPublications publications;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PublicationMetrics(
            JdbcPublications publications,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters) {
        this.publications = publications;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
        Gauge.builder(INCOMPLETE, this, metrics -> metrics.read(publications::countIncomplete))
                .description("Publications of domain events whose listener has not handled them yet")
                .register(meters);
        Gauge.builder(OLDEST_INCOMPLETE, this, PublicationMetrics::oldestIncompleteSeconds)
                .description("How long the oldest publication still being retried has waited")
                .baseUnit("seconds")
                .register(meters);
        Gauge.builder(FAILED, this, metrics -> metrics.read(publications::countDead))
                .description("Publications left for an operator after their last attempt")
                .register(meters);
    }

    /** Zero when nothing waits, and never below zero, since another instance's clock may be ahead. */
    static double ageInSeconds(Optional<Instant> oldest, Instant now) {
        return oldest.map(published ->
                        Math.max(0, Duration.between(published, now).toMillis() / 1000.0))
                .orElse(0.0);
    }

    private double oldestIncompleteSeconds() {
        Optional<OffsetDateTime> oldest = read(publications::oldestIncompleteAliveSince);
        return ageInSeconds(oldest.map(OffsetDateTime::toInstant), clock.instant());
    }

    private <T> T read(Supplier<T> query) {
        return Objects.requireNonNull(transactions.execute(status -> roles.call(ROLE, query)));
    }
}
