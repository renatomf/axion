package dev.axion.core.ledger.persistence;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static dev.axion.core.ledger.LedgerFixtures.newSource;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.ledger.EntryRequest;
import dev.axion.core.ledger.EntrySource;
import dev.axion.core.ledger.PostingRequest;
import dev.axion.core.ledger.domain.Account;
import dev.axion.core.ledger.domain.Entry;
import dev.axion.core.ledger.domain.Posting;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import java.time.Clock;
import java.time.LocalDate;
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
 * {@link JdbcEntries} as the ledger role. Tests roll back, so the deferred balance check never runs
 * here; {@code LedgerSchemaTest} covers it.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcEntriesTest {

    private static final Money TEN = Money.ofMinor(1_000, BRL);

    private final JdbcEntries entries;
    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UuidV7Generator ids = new UuidV7Generator(Clock.systemUTC());

    @Autowired
    JdbcEntriesTest(JdbcEntries entries, JdbcAccounts accounts, JdbcClient jdbc) {
        this.entries = entries;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
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
        var account = new Account(new AccountId(ids.next()), tenant, code, type, BRL, false, false);
        accounts.insertIfAbsent(account);
        return account;
    }

    /** Only the entry's row is under test here, so the request's postings are never read. */
    private EntryRequest request(EntrySource source) {
        return new EntryRequest(tenant, source, LocalDate.of(2026, 11, 18), "corr-1", List.of());
    }
}
