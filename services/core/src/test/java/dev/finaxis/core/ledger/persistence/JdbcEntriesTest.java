package dev.finaxis.core.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.ledger.domain.Entry;
import dev.finaxis.core.ledger.domain.Posting;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
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
 * {@link JdbcEntries} against a real PostgreSQL, as the ledger role. Each test runs in a transaction
 * that is rolled back when it ends, so the deferred balance check never runs: it is tested in
 * {@code LedgerSchemaTest}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcEntriesTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Money TEN = Money.ofMinor(1_000, BRL);

    private final JdbcEntries entries;
    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;
    private final UuidV7Generator ids = new UuidV7Generator(Clock.systemUTC());

    @Autowired
    JdbcEntriesTest(JdbcEntries entries, JdbcAccounts accounts, JdbcClient jdbc) {
        this.entries = entries;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        jdbc.sql("SET LOCAL ROLE finaxis_ledger").update();
    }

    @Test
    void claimsASourceOnlyOnce() {
        var request = request(newSource());
        var first = new EntryId(ids.next());

        assertThat(entries.claim(first, request)).isTrue();
        assertThat(entries.claim(new EntryId(ids.next()), request)).isFalse();

        assertThat(entries.findBySource(request.source()))
                .contains(new JdbcEntries.SourcedEntry(first, request.tenantId()));
    }

    @Test
    void claimsTheSameSourceForAnotherPurpose() {
        var source = newSource();
        var refund = new EntrySource(source.type(), source.id(), "refund");

        assertThat(entries.claim(new EntryId(ids.next()), request(source))).isTrue();
        assertThat(entries.claim(new EntryId(ids.next()), request(refund))).isTrue();
    }

    @Test
    void findsNoEntryForAnUnpostedSource() {
        assertThat(entries.findBySource(newSource())).isEmpty();
    }

    @Test
    void readsPostingsBack() {
        var settlement = open(AccountType.ASSET, "platform:settlement");
        var available = open(AccountType.LIABILITY, "tenant:available");
        var entry = new Entry(
                new EntryId(ids.next()), List.of(Posting.debit(settlement, TEN), Posting.credit(available, TEN)));
        entries.claim(entry.id(), request(newSource()));

        entries.insertPostings(entry, ids);

        assertThat(entries.findPostings(entry.id()))
                .containsExactlyInAnyOrder(
                        PostingRequest.debit(settlement.id(), TEN), PostingRequest.credit(available.id(), TEN));
    }

    private Account open(AccountType type, String code) {
        var account = new Account(new AccountId(ids.next()), UUID.randomUUID(), code, type, BRL, false, false);
        accounts.insertIfAbsent(account);
        return account;
    }

    private static EntrySource newSource() {
        return new EntrySource("charge", "ch_" + UUID.randomUUID(), "settlement");
    }

    /** Only the entry's row is under test here, so the request's postings are never read. */
    private static EntryRequest request(EntrySource source) {
        return new EntryRequest(UUID.randomUUID(), source, LocalDate.of(2026, 11, 18), "corr-1", List.of());
    }
}
