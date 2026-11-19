package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The ledger under concurrent load, against a real PostgreSQL (ledger design, section 8). Each call
 * to {@link LedgerApi#post} runs on its own thread, in its own transaction, so PostgreSQL's row
 * locks are what keeps the balances right.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerConcurrencyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Money ONE = Money.ofMinor(100, BRL);

    private final LedgerApi ledger;
    private final JdbcClient jdbc;

    private UUID tenant;
    private AccountId settlement;
    private AccountId available;

    @Autowired
    LedgerConcurrencyTest(LedgerApi ledger, JdbcClient jdbc) {
        this.ledger = ledger;
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
        assertThat(balanceOf(available)).isZero();
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
        assertThat(balanceOf(available)).isEqualTo(10_000);
        assertThat(balanceOf(held)).isEqualTo(10_000);
    }
    /**
     * Runs every call on its own thread and returns their results. A latch holds the threads until
     * all of them are ready, so the calls start together instead of one after another.
     */
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

    /**
     * Moves {@code amount} from one account to another: a debit of {@code from} and a credit of
     * {@code to}. On a liability, such as the tenant's, the debit lowers the balance (ledger design,
     * section 3.2).
     */
    private EntryRequest transfer(AccountId from, AccountId to, Money amount) {
        return new EntryRequest(
                tenant,
                new EntrySource("test", "tr_" + UUID.randomUUID(), "transfer"),
                LocalDate.of(2026, 11, 18),
                "corr-1",
                List.of(PostingRequest.debit(from, amount), PostingRequest.credit(to, amount)));
    }

    private long balanceOf(AccountId account) {
        return jdbc.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }

    private long postingsOf(AccountId account) {
        return jdbc.sql("SELECT count(*) FROM ledger.postings WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }
}
