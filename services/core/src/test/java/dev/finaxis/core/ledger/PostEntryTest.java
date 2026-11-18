package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Money;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
 * {@link LedgerApi#post} against a real PostgreSQL. Each call commits its own transaction, so the
 * deferred balance check runs, and every test uses new accounts and a new source.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PostEntryTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Money TEN = Money.ofMinor(1_000, BRL);

    private final LedgerApi ledger;
    private final JdbcClient jdbc;

    private AccountId settlement;
    private AccountId available;

    @Autowired
    PostEntryTest(LedgerApi ledger, JdbcClient jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void openAccounts() {
        var tenant = UUID.randomUUID();
        settlement = ledger.openAccount(
                new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, true, false));
        available = ledger.openAccount(
                new AccountRequest(tenant, "tenant:available", AccountType.LIABILITY, BRL, false, false));
    }

    @Test
    void postsABalancedEntry() {
        var result = ledger.post(settle(newSource(), TEN));

        assertThat(result).isInstanceOf(PostResult.Posted.class);
        assertThat(postingsOf(result.entry())).isEqualTo(2);
    }

    @Test
    void returnsTheSameEntryWhenPostedTwice() {
        var request = settle(newSource(), TEN);
        var first = ledger.post(request);

        var second = ledger.post(request);

        assertThat(second).isEqualTo(new PostResult.AlreadyPosted(first.entry()));
        assertThat(postingsOf(first.entry())).isEqualTo(2);
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
            var entry = results.getFirst().get().entry();
            for (Future<PostResult> result : results) {
                assertThat(result.get().entry()).isEqualTo(entry);
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

    private static EntryRequest request(EntrySource source, List<PostingRequest> postings) {
        return new EntryRequest(UUID.randomUUID(), source, LocalDate.of(2026, 11, 18), "corr-1", postings);
    }

    private static EntrySource newSource() {
        return new EntrySource("charge", "ch_" + UUID.randomUUID(), "settlement");
    }

    private long postingsOf(EntryId entry) {
        return jdbc.sql("SELECT count(*) FROM ledger.postings WHERE entry_id = ?")
                .param(entry.value())
                .query(Long.class)
                .single();
    }

    private long entriesOf(EntrySource source) {
        return jdbc.sql("SELECT count(*) FROM ledger.entries WHERE source_type = ? AND source_id = ? AND purpose = ?")
                .params(source.type(), source.id(), source.purpose())
                .query(Long.class)
                .single();
    }
}
