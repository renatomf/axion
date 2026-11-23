package dev.axion.core.ledger;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static dev.axion.core.ledger.LedgerFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.ledger.application.BalanceVerifier;
import dev.axion.core.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link LedgerApi#balanceOf} and {@link LedgerApi#history}; every test uses new accounts and sources. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerQueriesTest {

    /** Descending ID order, as PostgreSQL compares UUIDs: unsigned, byte by byte. */
    private static final Comparator<PostingId> NEWEST_FIRST =
            Comparator.comparing((PostingId id) -> id.value().toString()).reversed();

    private final LedgerApi api;
    private final BalanceVerifier verifier;
    private final JdbcClient owner;

    private UUID tenant;
    private TenantBoundLedger ledger;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    LedgerQueriesTest(LedgerApi api, BalanceVerifier verifier, DatabaseOwner databaseOwner) {
        this.api = api;
        this.verifier = verifier;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void openAccounts() {
        tenant = UUID.randomUUID();
        ledger = new TenantBoundLedger(api, tenant);
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

    @Test
    void readsAnEmptyHistory() {
        var page = ledger.history(available, Cursor.first(), 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void describesEachPostingOfTheAccount() {
        var source = settle(brl(1_000));

        var posting = ledger.history(available, Cursor.first(), 10).items().getFirst();

        assertThat(posting.source()).isEqualTo(source);
        assertThat(posting.direction()).isEqualTo(Direction.CREDIT);
        assertThat(posting.amount()).isEqualTo(brl(1_000));
        assertThat(posting.effectiveDate()).isEqualTo(LocalDate.of(2026, 11, 18));
    }

    @Test
    void listsOnlyTheAccountsPostingsInDescendingIdOrder() {
        var fees =
                ledger.openAccount(new AccountRequest(tenant, "platform:fees", AccountType.REVENUE, BRL, true, true));
        settle(brl(1_000));
        charge(fees, brl(30));
        settle(brl(500));

        var postings = ledger.history(available, Cursor.first(), 10).items();

        assertThat(postings).hasSize(3);
        assertThat(postings).extracting(PostingView::id).isSortedAccordingTo(NEWEST_FIRST);
        // The settlement account has the other legs of the two settlements only.
        assertThat(ledger.history(settlement, Cursor.first(), 10).items()).hasSize(2);
    }

    @Test
    void readsTheWholeHistoryPageByPageExactlyOnce() {
        for (int i = 1; i <= 7; i++) {
            settle(brl(i));
        }
        var all = ledger.history(available, Cursor.first(), 100).items();

        var read = new ArrayList<PostingView>();
        var page = ledger.history(available, Cursor.first(), 3);
        read.addAll(page.items());
        while (page.next().isPresent()) {
            page = ledger.history(available, page.next().get(), 3);
            read.addAll(page.items());
        }

        assertThat(read).isEqualTo(all).hasSize(7);
    }

    @Test
    void endsAFullLastPageWithoutACursor() {
        settle(brl(1));
        settle(brl(2));

        assertThat(ledger.history(available, Cursor.first(), 2).hasMore()).isFalse();
        assertThat(ledger.history(available, Cursor.first(), 1).hasMore()).isTrue();
    }

    @Test
    void rejectsALimitOutOfRange() {
        assertThatThrownBy(() -> ledger.history(available, Cursor.first(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ledger.history(available, Cursor.first(), LedgerApi.MAX_HISTORY_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTheHistoryOfAnUnknownAccount() {
        var unknown = new AccountId(UUID.randomUUID());

        assertThatThrownBy(() -> ledger.history(unknown, Cursor.first(), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown account");
    }

    /** Settles a payment: the platform receives the money and owes it to the tenant. */
    private EntrySource settle(Money amount) {
        return post(List.of(PostingRequest.debit(settlement, amount), PostingRequest.credit(available, amount)));
    }

    /** Charges the tenant a fee: its balance goes down and the platform's revenue goes up. */
    private void charge(AccountId fees, Money amount) {
        post(List.of(PostingRequest.debit(available, amount), PostingRequest.credit(fees, amount)));
    }

    private EntrySource post(List<PostingRequest> postings) {
        var source = new EntrySource("test", UUID.randomUUID().toString(), "test");
        var result = ledger.post(new EntryRequest(tenant, source, LocalDate.of(2026, 11, 18), "corr-test", postings));
        assertThat(result).isInstanceOf(PostResult.Posted.class);
        return source;
    }

    /** What the folding job will do (ledger design, section 5.3): fold committed postings into the balance row. */
    private void fold(AccountId hot) {
        owner.sql("""
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
}
