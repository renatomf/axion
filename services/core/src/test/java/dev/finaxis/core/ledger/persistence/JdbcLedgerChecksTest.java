package dev.finaxis.core.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcLedgerChecks}. Each test breaks the ledger in a transaction that rolls back; the clean
 * case also checks what other test classes committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcLedgerChecksTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Money TEN = Money.ofMinor(1_000, BRL);

    private final JdbcLedgerChecks checks;
    private final LedgerApi ledger;
    private final JdbcClient jdbc;

    private UUID tenant;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    JdbcLedgerChecksTest(JdbcLedgerChecks checks, LedgerApi ledger, JdbcClient jdbc) {
        this.checks = checks;
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void postASettlement() {
        tenant = UUID.randomUUID();
        settlement = ledger.openAccount(
                new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, true, false));
        available = ledger.openAccount(
                new AccountRequest(tenant, "tenant:available", AccountType.LIABILITY, BRL, false, false));
        ledger.post(transfer(settlement, available));
    }

    @Test
    void findsNothingWrongInALedgerWrittenThroughItsApi() {
        assertThat(checks.unbalancedEntries()).isEmpty();
        assertThat(checks.driftedBalances()).isEmpty();
        assertThat(checks.unbalancedCurrencies()).isEmpty();
    }

    @Test
    void findsABalanceChangedBehindTheLedgersBack() {
        jdbc.sql("UPDATE ledger.balances SET balance = balance + 1 WHERE account_id = ?")
                .param(available.value())
                .update();

        assertThat(checks.driftedBalances()).containsExactly(available);
    }

    @Test
    void findsAnEntryWhoseDebitsAndCreditsDiffer() {
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
                        """)
                .params(UUID.randomUUID(), entry, settlement.value(), tenant)
                .update();

        assertThat(checks.unbalancedEntries()).containsExactly(new EntryId(entry));
        assertThat(checks.unbalancedCurrencies()).containsExactly(BRL);
    }

    @Test
    void comparesAHotAccountOnlyWithThePostingsFoldedIntoIt() {
        var fees =
                ledger.openAccount(new AccountRequest(tenant, "platform:fees", AccountType.REVENUE, BRL, true, true));

        // A hot account's balance row is not touched when it is posted to (ledger design, section 5.3).
        ledger.post(transfer(settlement, fees));

        assertThat(checks.driftedBalances()).isEmpty();
    }

    /** Moves R$ 10.00 from one account to another: a debit of {@code from}, a credit of {@code to}. */
    private EntryRequest transfer(AccountId from, AccountId to) {
        var source = new EntrySource("charge", "ch_" + UUID.randomUUID(), "settlement");
        return new EntryRequest(
                tenant,
                source,
                LocalDate.of(2026, 11, 18),
                "corr-1",
                List.of(PostingRequest.debit(from, TEN), PostingRequest.credit(to, TEN)));
    }
}
