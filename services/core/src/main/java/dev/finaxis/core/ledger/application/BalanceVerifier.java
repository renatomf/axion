package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.persistence.JdbcLedgerChecks;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The second line of defense of the ledger (ledger design, section 7): recomputes invariants I1, I6
 * and I7 from the postings and reports any violation, which the first line, in the code and in the
 * database, should have made impossible.
 *
 * <p>It repairs nothing. A violation is an incident for a person to investigate, so it only
 * increments the {@value #VIOLATIONS} counter, tagged with the invariant, and logs an error with
 * the IDs involved. Rows hold no personal data, so the IDs are safe to log.
 */
@Service
public class BalanceVerifier {

    /** The counter's name; Prometheus shows it as {@code finaxis_ledger_invariant_violations_total}. */
    public static final String VIOLATIONS = "finaxis.ledger.invariant.violations";

    private static final Logger LOG = LoggerFactory.getLogger(BalanceVerifier.class);

    private final JdbcLedgerChecks checks;
    private final Counter unbalancedEntries;
    private final Counter driftedBalances;
    private final Counter unbalancedCurrencies;

    /**
     * Registers the three counters at zero, so each series exists before its first violation and an
     * alert can fire on the first increase.
     */
    public BalanceVerifier(JdbcLedgerChecks checks, MeterRegistry meters) {
        this.checks = checks;
        this.unbalancedEntries = violations(meters, "I1");
        this.driftedBalances = violations(meters, "I6");
        this.unbalancedCurrencies = violations(meters, "I7");
    }

    /**
     * Runs every check and reports what it found.
     *
     * <p>{@code REPEATABLE READ} makes every query of the transaction read the same snapshot of the
     * database, taken at its first query. Under the default {@code READ COMMITTED}, an entry
     * committed between the postings check and the balances check could look like a drifted
     * balance. {@code readOnly} makes PostgreSQL reject any write, so the verifier cannot change
     * what it checks.
     */
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
