package dev.axion.core.ledger.application;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.IntervalTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BalanceVerifier} (ledger design, section 8). The verifier joins the test's transaction, so
 * it sees the test's uncommitted damage and no other test does.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class BalanceVerifierTest {

    private final BalanceVerifier verifier;
    private final LedgerApi ledger;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    BalanceVerifierTest(BalanceVerifier verifier, LedgerApi ledger, JdbcClient jdbc, MeterRegistry meters) {
        this.verifier = verifier;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.meters = meters;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
    }

    @Test
    void passesACleanLedger() {
        var before = violations("I6");

        assertThat(verifier.verify().passed()).isTrue();
        assertThat(violations("I6")).isEqualTo(before);
    }

    @Test
    void reportsABalanceChangedBehindTheLedgersBack(CapturedOutput output) {
        var account = ledger.openAccount(
                new AccountRequest(tenant, "tenant:available", AccountType.LIABILITY, BRL, false, false));
        jdbc.sql("UPDATE ledger.balances SET balance = 100 WHERE account_id = ?")
                .param(account.value())
                .update();
        var before = violations("I6");

        var found = verifier.verify();

        assertThat(found.passed()).isFalse();
        assertThat(found.driftedBalances()).containsExactly(account);
        assertThat(violations("I6")).isEqualTo(before + 1);
        assertThat(output)
                .contains("Ledger invariant I6 is violated")
                .contains(account.value().toString());
    }

    @Test
    void reportsAnEntryWhoseDebitsAndCreditsDiffer(CapturedOutput output) {
        var account = ledger.openAccount(
                new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, true, false));
        // The deferred trigger would reject this entry at commit; the test rolls back before that.
        var entry = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger.entries (id, tenant_id, source_type, source_id, purpose, effective_date,
                                                    correlation_id)
                        VALUES (?, ?, 'test', ?, 'test', DATE '2026-11-18', 'corr-1')
                        """).params(entry, tenant, entry.toString()).update();
        jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account_id, tenant_id, currency, direction, amount)
                        VALUES (?, ?, ?, ?, 'BRL', 'DEBIT', 1)
                        """).params(UUID.randomUUID(), entry, account.value(), tenant).update();
        var beforeI1 = violations("I1");
        var beforeI7 = violations("I7");

        var found = verifier.verify();

        assertThat(found.passed()).isFalse();
        assertThat(violations("I1")).isEqualTo(beforeI1 + 1);
        assertThat(violations("I7")).isEqualTo(beforeI7 + 1);
        assertThat(output)
                .contains("Ledger invariant I1 is violated")
                .contains("Ledger invariant I7 is violated")
                .contains(entry.toString());
    }

    @Test
    void runsEveryFifteenMinutes(@Autowired ScheduledTaskHolder scheduler) {
        assertThat(scheduler.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(task -> task.toString().endsWith("BalanceVerifier.verify"))
                .singleElement()
                .isInstanceOfSatisfying(
                        IntervalTask.class,
                        task -> assertThat(task.getIntervalDuration()).isEqualTo(Duration.ofMinutes(15)));
    }

    /** The counter is cumulative over the whole test run, so tests compare it before and after. */
    private double violations(String invariant) {
        return meters.get(BalanceVerifier.VIOLATIONS)
                .tag("invariant", invariant)
                .counter()
                .count();
    }
}
