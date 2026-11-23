package dev.finaxis.core.rails.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.rails.persistence.JdbcPixCredits;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How long the oldest credit of the inbox has waited to be recorded by billing (billing design,
 * section 7.3), as {@code finaxis_rails_credit_oldest_pending_seconds}: the alert's signal that
 * money arrived and is not in any balance yet. Read from the inbox when the gauge is read, so it
 * keeps rising when the processor itself has stopped.
 */
@Component
class PendingCredits {

    static final String OLDEST_PENDING = "finaxis.rails.credit.oldest.pending";

    private static final String ROLE = "finaxis_rails";

    private final JdbcPixCredits credits;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;

    PendingCredits(
            JdbcPixCredits credits,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters) {
        this.credits = credits;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
        Gauge.builder(OLDEST_PENDING, this, PendingCredits::oldestPendingSeconds)
                .description("How long the oldest credit of the inbox has waited to be recorded by billing")
                .baseUnit("seconds")
                .register(meters);
    }

    /**
     * The age of the oldest pending credit, in seconds; zero when none is pending, and never below
     * zero, since another instance's clock may be slightly ahead.
     */
    static double ageInSeconds(Optional<Instant> oldest, Instant now) {
        return oldest.map(
                        received -> Math.max(0, Duration.between(received, now).toMillis() / 1000.0))
                .orElse(0.0);
    }

    private double oldestPendingSeconds() {
        Optional<Instant> oldest = Objects.requireNonNull(
                transactions.execute(status -> roles.call(ROLE, credits::oldestPendingReceivedAt)));
        return ageInSeconds(oldest, clock.instant());
    }
}
