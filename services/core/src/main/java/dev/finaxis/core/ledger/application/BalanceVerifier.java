package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.persistence.JdbcLedgerChecks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's second line of defense (ledger design, section 7): recomputes invariants I1, I6 and
 * I7 and reports violations through a counter and an error log. It repairs nothing: a violation is
 * an incident for a person.
 */
@Service
public class BalanceVerifier {

    public static final String VIOLATIONS = "finaxis.ledger.invariant.violations";

    private static final Logger LOG = LoggerFactory.getLogger(BalanceVerifier.class);

    private final JdbcLedgerChecks checks;
    private final Counter unbalancedEntries;
    private final Counter driftedBalances;
    private final Counter unbalancedCurrencies;

    // Registered at zero, so an alert fires on the first violation.
    public BalanceVerifier(JdbcLedgerChecks checks, MeterRegistry meters) {
        this.checks = checks;
        this.unbalancedEntries = violations(meters, "I1");
        this.driftedBalances = violations(meters, "I6");
        this.unbalancedCurrencies = violations(meters, "I7");
    }

    // REPEATABLE READ: every check reads one snapshot, so an entry committed between two checks cannot
    // look like a drifted balance. Read-only, so the verifier cannot change what it checks.
    @Scheduled(initialDelay = 15, fixedDelay = 15, timeUnit = TimeUnit.MINUTES)
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public LedgerVerification verify() {
        var found = new LedgerVerification(
                checks.unbalancedEntries(), checks.driftedBalances(), checks.unbalancedCurrencies());
        report("I1", unbalancedEntries, found.unbalancedEntries());
        report("I6", driftedBalances, found.driftedBalances());
        report("I7", unbalancedCurrencies, found.unbalancedCurrencies());
        return found;
    }

    private static void report(String invariant, Counter counter, List<?> violations) {
        if (violations.isEmpty()) {
            return;
        }
        counter.increment(violations.size());
        LOG.error("Ledger invariant {} is violated by {} rows: {}", invariant, violations.size(), violations);
    }

    private static Counter violations(MeterRegistry meters, String invariant) {
        return Counter.builder(VIOLATIONS)
                .description("Ledger rows found breaking an invariant by the balance verifier")
                .tag("invariant", invariant)
                .register(meters);
    }
}
