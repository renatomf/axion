package dev.finaxis.core.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.LedgerApi;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
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
 * {@link BalanceVerifier} against a real PostgreSQL (ledger design, section 8). Each test runs in a
 * transaction that is rolled back when it ends; the verifier joins it, so it sees the test's
 * uncommitted damage and no other test does.
 *
 * <p>{@link OutputCaptureExtension} records what the test writes to the console, logs included.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class BalanceVerifierTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final BalanceVerifier verifier;
    private final LedgerApi ledger;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    @Autowired
    BalanceVerifierTest(BalanceVerifier verifier, LedgerApi ledger, JdbcClient jdbc, MeterRegistry meters) {
        this.verifier = verifier;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.meters = meters;
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
                new AccountRequest(UUID.randomUUID(), "tenant:available", AccountType.LIABILITY, BRL, false, false));
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
