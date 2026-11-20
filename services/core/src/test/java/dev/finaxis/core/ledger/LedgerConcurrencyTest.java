package dev.finaxis.core.ledger;

import static dev.finaxis.core.ledger.LedgerFixtures.BRL;
import static dev.finaxis.core.ledger.LedgerFixtures.storedBalance;
import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.application.BalanceVerifier;
import dev.finaxis.core.ledger.application.LedgerVerification;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The ledger under concurrent load (ledger design, section 8): each post runs on its own thread and
 * transaction, so PostgreSQL's row locks are what keeps the balances right.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerConcurrencyTest {

    private static final Money ONE = Money.ofMinor(100, BRL);

    private final LedgerApi ledger;
    private final JdbcClient jdbc;
    private final JdbcClient owner;
    private final TransactionTemplate transaction;
    private final BalanceVerifier verifier;

    private UUID tenant;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    LedgerConcurrencyTest(
            LedgerApi ledger,
            JdbcClient jdbc,
            DatabaseOwner databaseOwner,
            PlatformTransactionManager transactions,
            BalanceVerifier verifier) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.owner = databaseOwner.jdbc();
        this.transaction = new TransactionTemplate(transactions);
        this.verifier = verifier;
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
        assertThat(verifier.verify()).isEqualTo(new LedgerVerification(List.of(), List.of(), List.of()));
    }

    @Test
    void neverOverdrawsABalanceUnderConcurrentDebits() throws InterruptedException, ExecutionException {
        ledger.post(transfer(settlement, available, Money.ofMinor(5_000, BRL)));
        var payouts = new ArrayList<Callable<PostResult>>();
        for (int i = 0; i < 100; i++) {
            var payout = transfer(available, settlement, ONE);
            payouts.add(() -> ledger.post(payout));
        }

        var results = postAtOnce(payouts);

        // R$ 50.00 pays exactly fifty payouts of R$ 1.00; every other one finds the balance short.
        assertThat(results).filteredOn(PostResult.Posted.class::isInstance).hasSize(50);
        assertThat(results)
                .filteredOn(new PostResult.InsufficientFunds(available)::equals)
                .hasSize(50);
        assertThat(storedBalance(owner, available)).isZero();
        assertThat(postingsOf(available)).isEqualTo(51);
    }

    @Test
    void finishesEntriesThatTouchTheSameAccountsInOppositeOrders() throws InterruptedException, ExecutionException {
        var held =
                ledger.openAccount(new AccountRequest(tenant, "tenant:held", AccountType.LIABILITY, BRL, false, false));
        var funds = Money.ofMinor(10_000, BRL);
        ledger.post(transfer(settlement, available, funds));
        ledger.post(transfer(settlement, held, funds));
        // Half the transfers list available first and half list held first. If balances were
        // locked in that order, two of them could each hold the row the other needs.
        var transfers = new ArrayList<Callable<PostResult>>();
        for (int i = 0; i < 100; i++) {
            var there = transfer(available, held, ONE);
            var back = transfer(held, available, ONE);
            transfers.add(() -> ledger.post(there));
            transfers.add(() -> ledger.post(back));
        }

        var results = postAtOnce(transfers);

        // Each balance starts with enough for every transfer out of it, so none is refused, and
        // postAtOnce would have thrown on a deadlock.
        assertThat(results).allMatch(PostResult.Posted.class::isInstance);
        assertThat(storedBalance(owner, available)).isEqualTo(10_000);
        assertThat(storedBalance(owner, held)).isEqualTo(10_000);
    }

    /**
     * The control for the test above: two transactions that lock the same two balance rows in
     * opposite orders do deadlock, and PostgreSQL aborts one of them. So a test that finishes with
     * no deadlock shows the lock order works, not that the scenario could not happen.
     */
    @Test
    void deadlocksWhenTwoTransactionsLockRowsInOppositeOrders() throws InterruptedException {
        var held =
                ledger.openAccount(new AccountRequest(tenant, "tenant:held", AccountType.LIABILITY, BRL, false, false));
        // Both transactions hold their first lock before either asks for its second.
        var bothLockedOne = new CyclicBarrier(2);
        Callable<Void> availableFirst = () -> lockInOrder(available, held, bothLockedOne);
        Callable<Void> heldFirst = () -> lockInOrder(held, available, bothLockedOne);

        try (var threads = Executors.newFixedThreadPool(2)) {
            var outcomes = threads.invokeAll(List.of(availableFirst, heldFirst));
            var failures = new ArrayList<Throwable>();
            for (var outcome : outcomes) {
                try {
                    outcome.get();
                } catch (ExecutionException failed) {
                    failures.add(failed.getCause());
                }
            }
            // PostgreSQL aborts exactly one of them, and the other then gets its second lock.
            assertThat(failures)
                    .singleElement()
                    .satisfies(failure -> assertThat(failure)
                            .isInstanceOf(PessimisticLockingFailureException.class)
                            .hasMessageContaining("deadlock detected"));
        }
    }

    /**
     * Locks two balance rows in one transaction, the first one before waiting at the barrier. The
     * update adds zero, so it takes the row lock without changing the balance.
     */
    private @Nullable Void lockInOrder(AccountId first, AccountId second, CyclicBarrier barrier) {
        return transaction.execute(status -> {
            DatabaseRole.LEDGER.actAs(jdbc);
            lockBalance(first);
            try {
                barrier.await();
            } catch (InterruptedException | BrokenBarrierException interrupted) {
                throw new IllegalStateException(interrupted);
            }
            lockBalance(second);
            return null;
        });
    }

    private void lockBalance(AccountId account) {
        jdbc.sql("UPDATE ledger.balances SET balance = balance WHERE account_id = ?")
                .param(account.value())
                .update();
    }

    /** Starts every call together, each on its own thread. */
    private static <T> List<T> postAtOnce(List<Callable<T>> calls) throws InterruptedException, ExecutionException {
        var ready = new CountDownLatch(calls.size());
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(calls.size())) {
            var futures = new ArrayList<Future<T>>();
            for (var call : calls) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return call.call();
                }));
            }
            ready.await();
            start.countDown();
            var results = new ArrayList<T>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private EntryRequest transfer(AccountId from, AccountId to, Money amount) {
        return new EntryRequest(
                tenant,
                new EntrySource("test", "tr_" + UUID.randomUUID(), "transfer"),
                LocalDate.of(2026, 11, 18),
                "corr-1",
                List.of(PostingRequest.debit(from, amount), PostingRequest.credit(to, amount)));
    }

    private long postingsOf(AccountId account) {
        return owner.sql("SELECT count(*) FROM ledger.postings WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }
}
