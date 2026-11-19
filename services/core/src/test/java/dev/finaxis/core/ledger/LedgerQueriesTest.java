package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.application.BalanceVerifier;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link LedgerApi#balanceOf} against a real PostgreSQL. Each call commits its own transaction, and
 * every test uses new accounts and new sources.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerQueriesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final LedgerApi ledger;
    private final BalanceVerifier verifier;
    private final JdbcClient jdbc;

    private UUID tenant;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    LedgerQueriesTest(LedgerApi ledger, BalanceVerifier verifier, JdbcClient jdbc) {
        this.ledger = ledger;
        this.verifier = verifier;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void openAccounts() {
        tenant = UUID.randomUUID();
        settlement = ledger.openAccount(
                new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, true, false));
        available = ledger.openAccount(
                new AccountRequest(tenant, "tenant:available", AccountType.LIABILITY, BRL, false, false));
    }

    /** What a test commits must leave the ledger's invariants intact (ledger design, section 8). */
    @AfterEach
    void leavesTheLedgerSound() {
        assertThat(verifier.verify().passed()).isTrue();
    }

    @Test
    void readsANewAccountAtZero() {
        var balance = ledger.balanceOf(available);

        assertThat(balance.account()).isEqualTo(available);
        assertThat(balance.balance()).isEqualTo(Money.zero(BRL));
    }

    @Test
    void readsTheBalanceThePostingsLeft() {
        settle(brl(1_000));
        settle(brl(250));

        assertThat(ledger.balanceOf(available).balance()).isEqualTo(brl(1_250));
        assertThat(ledger.balanceOf(settlement).balance()).isEqualTo(brl(1_250));
    }

    @Test
    void addsTheUnfoldedPostingsOfAHotAccount() {
        var fees =
                ledger.openAccount(new AccountRequest(tenant, "platform:fees", AccountType.REVENUE, BRL, true, true));
        // The tenant pays a fee twice and gets part of one back: two credits and a debit to revenue.
        settle(brl(1_000));
        charge(fees, brl(30));
        charge(fees, brl(20));
        post(List.of(PostingRequest.debit(fees, brl(5)), PostingRequest.credit(available, brl(5))));

        assertThat(ledger.balanceOf(fees).balance()).isEqualTo(brl(45));
        assertThat(ledger.balanceOf(available).balance()).isEqualTo(brl(955));
    }

    @Test
    void countsTheFoldedPostingsOfAHotAccountOnce() {
        var fees =
                ledger.openAccount(new AccountRequest(tenant, "platform:fees", AccountType.REVENUE, BRL, true, true));
        settle(brl(1_000));
        charge(fees, brl(30));
        fold(fees);
        charge(fees, brl(20));

        assertThat(ledger.balanceOf(fees).balance()).isEqualTo(brl(50));
    }

    @Test
    void rejectsAnUnknownAccount() {
        var unknown = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> ledger.balanceOf(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown account");
    }

    /** Settles a payment: the platform receives the money and owes it to the tenant. */
    private void settle(Money amount) {
        post(List.of(PostingRequest.debit(settlement, amount), PostingRequest.credit(available, amount)));
    }

    /** Charges the tenant a fee: its balance goes down and the platform's revenue goes up. */
    private void charge(AccountId fees, Money amount) {
        post(List.of(PostingRequest.debit(available, amount), PostingRequest.credit(fees, amount)));
    }

    private void post(List<PostingRequest> postings) {
        var source = new EntrySource("test", UUID.randomUUID().toString(), "test");
        var result = ledger.post(new EntryRequest(tenant, source, LocalDate.of(2026, 11, 18), "corr-test", postings));
        assertThat(result).isInstanceOf(PostResult.Posted.class);
    }

    /**
     * Does what the folding job will (ledger design, section 5.3): moves the postings committed so
     * far into the balance row. They were created before this statement's transaction started.
     */
    private void fold(AccountId hot) {
        jdbc.sql("""
                        UPDATE ledger.balances b
                           SET balance = (SELECT sum(CASE WHEN p.direction = a.normal_balance
                                                          THEN p.amount ELSE -p.amount END)
                                            FROM ledger.postings p
                                            JOIN ledger.accounts a ON a.id = p.account_id
                                           WHERE p.account_id = b.account_id AND p.created_at < now()),
                               folded_until = now()
                         WHERE account_id = :id
                        """).param("id", hot.value()).update();
    }

    private static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, BRL);
    }
}
