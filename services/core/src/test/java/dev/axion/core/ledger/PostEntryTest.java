package dev.axion.core.ledger;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static dev.axion.core.ledger.LedgerFixtures.newSource;
import static dev.axion.core.ledger.LedgerFixtures.storedBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.ledger.application.BalanceVerifier;
import dev.axion.core.ledger.application.LedgerVerification;
import dev.axion.core.shared.Money;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link LedgerApi#post}. Each call commits, so the deferred balance check runs. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PostEntryTest {

    private static final Money TEN = Money.ofMinor(1_000, BRL);

    private final LedgerApi api;
    private final JdbcClient owner;
    private final TransactionTemplate transaction;
    private final BalanceVerifier verifier;

    private UUID tenant;
    private TenantBoundLedger ledger;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    PostEntryTest(
            LedgerApi api,
            DatabaseOwner databaseOwner,
            PlatformTransactionManager transactions,
            BalanceVerifier verifier) {
        this.api = api;
        this.owner = databaseOwner.jdbc();
        this.transaction = new TransactionTemplate(transactions);
        this.verifier = verifier;
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
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void postsABalancedEntry() {
        var result = ledger.post(settle(newSource(), TEN));

        assertThat(result).isInstanceOf(PostResult.Posted.class);
        assertThat(postingsOf(entryOf(result))).isEqualTo(2);
    }

    @Test
    void updatesTheBalanceOfEachAccount() {
        ledger.post(settle(newSource(), TEN));

        // A debit raises an asset and a credit raises a liability (ledger design, section 3.2).
        assertThat(storedBalance(owner, settlement)).isEqualTo(1_000);
        assertThat(storedBalance(owner, available)).isEqualTo(1_000);
    }

    @Test
    void refusesAnEntryThatWouldOverdrawAGuardedAccount() {
        ledger.post(settle(newSource(), TEN));
        var payout = payOut(newSource(), Money.ofMinor(1_001, BRL));

        var result = ledger.post(payout);

        assertThat(result).isEqualTo(new PostResult.InsufficientFunds(available));
        assertThat(entriesOf(payout.source())).isZero();
        // The settlement account was changed first, then rolled back with the rest.
        assertThat(storedBalance(owner, settlement)).isEqualTo(1_000);
        assertThat(storedBalance(owner, available)).isEqualTo(1_000);
    }

    @Test
    void checksBalancesInAccountIdOrder() {
        var held =
                ledger.openAccount(new AccountRequest(tenant, "tenant:held", AccountType.LIABILITY, BRL, false, false));
        var first = available.value().compareTo(held.value()) < 0 ? available : held;
        var second = first.equals(available) ? held : available;
        // Both tenant balances are zero, so both are short; the postings list the later one first.
        var request = request(
                newSource(),
                List.of(
                        PostingRequest.debit(second, TEN),
                        PostingRequest.debit(first, TEN),
                        PostingRequest.credit(settlement, Money.ofMinor(2_000, BRL))));

        assertThat(ledger.post(request)).isEqualTo(new PostResult.InsufficientFunds(first));
    }

    @Test
    void postsARefusedSourceOnceTheFundsAreThere() {
        var payout = payOut(newSource(), TEN);
        ledger.post(payout);
        ledger.post(settle(newSource(), TEN));

        var result = ledger.post(payout);

        assertThat(result).isInstanceOf(PostResult.Posted.class);
        assertThat(storedBalance(owner, available)).isZero();
    }

    @Test
    void keepsTheCallersWorkWhenFundsAreShort() {
        var callersAccount = ledger.call(() -> transaction.execute(status -> {
            var opened = ledger.openAccount(
                    new AccountRequest(tenant, "tenant:held", AccountType.LIABILITY, BRL, false, false));
            assertThat(ledger.post(payOut(newSource(), TEN))).isInstanceOf(PostResult.InsufficientFunds.class);
            return opened;
        }));

        assertThat(storedBalance(owner, callersAccount)).isZero();
    }

    @Test
    void rollsTheCallerBackWhenPostingThrows() {
        var source = newSource();
        ledger.post(settle(source, TEN));

        // The caller swallows the ledger's exception and tries to commit its own work anyway.
        assertThatThrownBy(() -> ledger.run(() -> transaction.executeWithoutResult(status -> {
                    ledger.openAccount(
                            new AccountRequest(tenant, "tenant:held", AccountType.LIABILITY, BRL, false, false));
                    try {
                        ledger.post(settle(source, Money.ofMinor(2_000, BRL)));
                    } catch (IllegalStateException swallowed) {
                        // A bug in the caller: the ledger must still fail closed.
                    }
                })))
                .isInstanceOf(UnexpectedRollbackException.class);

        assertThat(accountsWithCode("tenant:held")).isZero();
    }

    @Test
    void leavesTheBalanceOfAHotAccountToTheFoldingJob() {
        var hot = ledger.openAccount(new AccountRequest(tenant, "platform:fees", AccountType.ASSET, BRL, true, true));

        ledger.post(
                request(newSource(), List.of(PostingRequest.debit(hot, TEN), PostingRequest.credit(available, TEN))));

        assertThat(storedBalance(owner, hot)).isZero();
        assertThat(storedBalance(owner, available)).isEqualTo(1_000);
    }

    @Test
    void returnsTheSameEntryWhenPostedTwice() {
        var request = settle(newSource(), TEN);
        var first = ledger.post(request);

        var second = ledger.post(request);

        assertThat(second).isEqualTo(new PostResult.AlreadyPosted(entryOf(first)));
        assertThat(postingsOf(entryOf(first))).isEqualTo(2);
        assertThat(storedBalance(owner, available)).isEqualTo(1_000);
    }

    @Test
    void rejectsASourceReusedWithOtherPostings() {
        var source = newSource();
        ledger.post(settle(source, TEN));

        assertThatThrownBy(() -> ledger.post(settle(source, Money.ofMinor(2_000, BRL))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already posted with other postings");
    }

    @Test
    void rejectsASourceReusedForAnotherTenant() {
        var source = newSource();
        ledger.post(settle(source, TEN));
        var other = UUID.randomUUID();
        var otherLedger = new TenantBoundLedger(api, other);
        var otherSettlement = otherLedger.openAccount(
                new AccountRequest(other, "platform:settlement", AccountType.ASSET, BRL, true, false));
        var otherAvailable = otherLedger.openAccount(
                new AccountRequest(other, "tenant:available", AccountType.LIABILITY, BRL, false, false));
        var otherTenant = new EntryRequest(
                other,
                source,
                LocalDate.of(2026, 11, 18),
                "corr-1",
                List.of(PostingRequest.debit(otherSettlement, TEN), PostingRequest.credit(otherAvailable, TEN)));

        assertThatThrownBy(() -> otherLedger.post(otherTenant))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already posted for another tenant");
    }

    @Test
    void rejectsAnUnbalancedEntry() {
        var request = request(
                newSource(),
                List.of(
                        PostingRequest.debit(settlement, TEN),
                        PostingRequest.credit(available, Money.ofMinor(999, BRL))));

        assertThatThrownBy(() -> ledger.post(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(entriesOf(request.source())).isZero();
    }

    @Test
    void rejectsAnUnknownAccount() {
        var unknown = new AccountId(UUID.randomUUID());
        var request = request(
                newSource(), List.of(PostingRequest.debit(settlement, TEN), PostingRequest.credit(unknown, TEN)));

        assertThatThrownBy(() -> ledger.post(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown account");
        assertThat(entriesOf(request.source())).isZero();
    }

    @Test
    void postsOneEntryWhenManyCallersPostItAtOnce() throws InterruptedException, ExecutionException {
        var request = settle(newSource(), TEN);
        Callable<PostResult> post = () -> ledger.post(request);

        // Fifty threads, each with its own transaction, post the same source at once.
        try (var threads = Executors.newFixedThreadPool(50)) {
            var results = threads.invokeAll(Collections.nCopies(50, post));
            var posted = 0;
            var entry = entryOf(results.getFirst().get());
            for (Future<PostResult> result : results) {
                assertThat(entryOf(result.get())).isEqualTo(entry);
                if (result.get() instanceof PostResult.Posted) {
                    posted++;
                }
            }
            assertThat(posted).isOne();
            assertThat(postingsOf(entry)).isEqualTo(2);
        }
        assertThat(entriesOf(request.source())).isOne();
    }

    /** A settlement: the platform receives the amount and owes it to the tenant. */
    private EntryRequest settle(EntrySource source, Money amount) {
        return request(
                source, List.of(PostingRequest.debit(settlement, amount), PostingRequest.credit(available, amount)));
    }

    /**
     * A payout: the tenant's balance goes down and the platform pays the amount out. The settlement
     * leg comes first, so its balance is changed before the tenant's is checked.
     */
    private EntryRequest payOut(EntrySource source, Money amount) {
        return request(
                source, List.of(PostingRequest.credit(settlement, amount), PostingRequest.debit(available, amount)));
    }

    private EntryRequest request(EntrySource source, List<PostingRequest> postings) {
        return new EntryRequest(tenant, source, LocalDate.of(2026, 11, 18), "corr-1", postings);
    }

    private static EntryId entryOf(PostResult result) {
        return switch (result) {
            case PostResult.Posted posted -> posted.entry();
            case PostResult.AlreadyPosted already -> already.entry();
            case PostResult.InsufficientFunds insufficient -> throw new AssertionError("Unexpected " + insufficient);
        };
    }

    private long accountsWithCode(String code) {
        return owner.sql("SELECT count(*) FROM ledger.accounts WHERE tenant_id = ? AND code = ?")
                .params(tenant, code)
                .query(Long.class)
                .single();
    }

    private long postingsOf(EntryId entry) {
        return owner.sql("SELECT count(*) FROM ledger.postings WHERE entry_id = ?")
                .param(entry.value())
                .query(Long.class)
                .single();
    }

    private long entriesOf(EntrySource source) {
        return owner.sql("SELECT count(*) FROM ledger.entries WHERE source_type = ? AND source_id = ? AND purpose = ?")
                .params(source.type(), source.id(), source.purpose())
                .query(Long.class)
                .single();
    }
}
