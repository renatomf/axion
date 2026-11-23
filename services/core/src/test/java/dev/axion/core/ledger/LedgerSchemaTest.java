package dev.axion.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The rules the database enforces on the ledger's tables (ledger design, sections 3.3 and 4). Rules
 * checked at commit need a real commit, so those tests commit, each with a new tenant.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerSchemaTest {

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;

    @Autowired
    LedgerSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void commitsABalancedEntry() {
        var tenant = UUID.randomUUID();
        var entry = UUID.randomUUID();

        committed(() -> {
            DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
            var settlement = insertAccount(tenant, "ASSET", "DEBIT", "BRL");
            var available = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
            insertEntry(entry, tenant);
            insertPosting(entry, settlement, tenant, "BRL", "DEBIT", 100);
            insertPosting(entry, available, tenant, "BRL", "CREDIT", 100);
            // The rows stay committed, so their balances must match their postings, as the ledger
            // would have written them; otherwise the balance verifier would report them (I6).
            insertBalance(settlement, 100);
            insertBalance(available, 100);
        });

        assertThat(entryExists(entry)).isTrue();
    }

    @Test
    void rejectsAnUnbalancedEntryAtCommit() {
        var tenant = UUID.randomUUID();
        var entry = UUID.randomUUID();

        // Each insert succeeds; the commit fails and rolls everything back.
        assertThatThrownBy(() -> committed(() -> {
                    DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
                    var settlement = insertAccount(tenant, "ASSET", "DEBIT", "BRL");
                    var available = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
                    insertEntry(entry, tenant);
                    insertPosting(entry, settlement, tenant, "BRL", "DEBIT", 100);
                    insertPosting(entry, available, tenant, "BRL", "CREDIT", 90);
                }))
                .rootCause()
                .hasMessageContaining("does not balance");
        assertThat(entryExists(entry)).isFalse();
    }

    @Test
    void rejectsAnEntryWithoutPostingsAtCommit() {
        var tenant = UUID.randomUUID();
        var entry = UUID.randomUUID();

        assertThatThrownBy(() -> committed(() -> {
                    DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
                    insertEntry(entry, tenant);
                }))
                .rootCause()
                .hasMessageContaining("fewer than two postings");
        assertThat(entryExists(entry)).isFalse();
    }

    @Test
    void commitsABalancedEntryAfterTheLedgersRoleIsRestored() {
        var tenant = UUID.randomUUID();
        var entry = UUID.randomUUID();

        // The ledger's API returns before the commit, restoring the caller's role, which may read
        // no ledger table.
        committed(() -> {
            DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
            var settlement = insertAccount(tenant, "ASSET", "DEBIT", "BRL");
            var available = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
            insertEntry(entry, tenant);
            insertPosting(entry, settlement, tenant, "BRL", "DEBIT", 100);
            insertPosting(entry, available, tenant, "BRL", "CREDIT", 100);
            insertBalance(settlement, 100);
            insertBalance(available, 100);
            DatabaseRole.TENANCY.actAs(jdbc);
        });

        assertThat(entryExists(entry)).isTrue();
    }

    @Test
    void rejectsAnUnbalancedEntryAfterTheLedgersRoleIsRestored() {
        var tenant = UUID.randomUUID();
        var entry = UUID.randomUUID();

        assertThatThrownBy(() -> committed(() -> {
                    DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
                    var settlement = insertAccount(tenant, "ASSET", "DEBIT", "BRL");
                    var available = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
                    insertEntry(entry, tenant);
                    insertPosting(entry, settlement, tenant, "BRL", "DEBIT", 100);
                    insertPosting(entry, available, tenant, "BRL", "CREDIT", 90);
                    DatabaseRole.TENANCY.actAs(jdbc);
                }))
                .rootCause()
                .hasMessageContaining("does not balance");
        assertThat(entryExists(entry)).isFalse();
    }

    @Test
    void rejectsAnAccountWhoseNormalBalanceContradictsItsType() {
        assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "ASSET", "CREDIT", "BRL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAPostingInAnotherCurrencyThanItsAccount() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        var entry = insertEntry(tenant);

        assertThatThrownBy(() -> insertPosting(entry, account, tenant, "USD", "CREDIT", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsAPostingWithoutAPositiveAmount() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        var entry = insertEntry(tenant);

        assertThatThrownBy(() -> insertPosting(entry, account, tenant, "BRL", "CREDIT", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUpdatingAPostingEvenAsTheOwner() {
        // These tests connect as the database owner, who holds every privilege:
        // only the trigger stands in the way.
        var entry = insertEntryWithOnePosting();

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.postings SET amount = 1 WHERE entry_id = ?")
                        .param(entry)
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    void rejectsDeletingAnEntryEvenAsTheOwner() {
        var entry = insertEntryWithOnePosting();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.entries WHERE id = ?")
                        .param(entry)
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    void rejectsTruncatingPostings() {
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger.postings").update()).hasMessageContaining("append-only");
    }

    @Test
    void deniesTheLedgerRoleUpdatingPostings() {
        var entry = insertEntryWithOnePosting();
        DatabaseRole.LEDGER.actAs(jdbc);

        // Denied by privileges, before any trigger runs.
        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.postings SET amount = 1 WHERE entry_id = ?")
                        .param(entry)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheLedgerRoleDeletingEntries() {
        var entry = insertEntryWithOnePosting();
        DatabaseRole.LEDGER.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.entries WHERE id = ?")
                        .param(entry)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheLedgerRoleUpdatingAccounts() {
        var account = insertAccount(UUID.randomUUID(), "LIABILITY", "CREDIT", "BRL");
        DatabaseRole.LEDGER.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.accounts SET allow_negative = true WHERE id = ?")
                        .param(account)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheLedgerRoleUpdateBalances() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        jdbc.sql("INSERT INTO ledger.balances (account_id) VALUES (?)")
                .param(account)
                .update();
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));

        var updated = jdbc.sql("UPDATE ledger.balances SET balance = balance + 100 WHERE account_id = ?")
                .param(account)
                .update();

        assertThat(updated).isOne();
    }

    @Test
    void letsTheJobsRoleReadTheLedger() {
        var entry = insertEntryWithOnePosting();
        DatabaseRole.LEDGER_JOBS.actAs(jdbc);

        var read = jdbc.sql("""
                        SELECT count(*)
                          FROM ledger.entries e
                          JOIN ledger.postings p ON p.entry_id = e.id
                          JOIN ledger.accounts a ON a.id = p.account_id
                          LEFT JOIN ledger.balances b ON b.account_id = a.id
                         WHERE e.id = ?
                        """).param(entry).query(Long.class).single();

        assertThat(read).isOne();
    }

    @Test
    void deniesTheJobsRoleUpdatingBalances() {
        var account = insertAccount(UUID.randomUUID(), "LIABILITY", "CREDIT", "BRL");
        insertBalance(account, 0);
        DatabaseRole.LEDGER_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("UPDATE ledger.balances SET balance = 100 WHERE account_id = ?")
                        .param(account)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheJobsRoleInsertingEntries() {
        DatabaseRole.LEDGER_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> insertEntry(UUID.randomUUID())).rootCause().hasMessageContaining("permission denied");
    }

    private void committed(Runnable work) {
        work.run();
        session.commit();
    }

    private UUID insertEntryWithOnePosting() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        var entry = insertEntry(tenant);
        insertPosting(entry, account, tenant, "BRL", "CREDIT", 100);
        return entry;
    }

    private UUID insertAccount(UUID tenant, String type, String normalBalance, String currency) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger.accounts
                            (id, tenant_id, code, type, normal_balance, currency, allow_negative, is_hot)
                        VALUES (?, ?, ?, ?, ?, ?, false, false)
                        """)
                .params(id, tenant, "tenant:" + tenant + ":" + id, type, normalBalance, currency)
                .update();
        return id;
    }

    private void insertBalance(UUID account, long balance) {
        jdbc.sql("INSERT INTO ledger.balances (account_id, balance) VALUES (?, ?)")
                .params(account, balance)
                .update();
    }

    private UUID insertEntry(UUID tenant) {
        return insertEntry(UUID.randomUUID(), tenant);
    }

    private UUID insertEntry(UUID id, UUID tenant) {
        jdbc.sql("""
                        INSERT INTO ledger.entries
                            (id, tenant_id, source_type, source_id, purpose, effective_date, correlation_id)
                        VALUES (?, ?, 'test', ?, 'test', DATE '2026-11-18', 'test')
                        """).params(id, tenant, id.toString()).update();
        return id;
    }

    private boolean entryExists(UUID entry) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ledger.entries WHERE id = ?)")
                .param(entry)
                .query(Boolean.class)
                .single();
    }

    private void insertPosting(UUID entry, UUID account, UUID tenant, String currency, String direction, long amount) {
        jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account_id, tenant_id, currency, direction, amount)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(UUID.randomUUID(), entry, account, tenant, currency, direction, amount)
                .update();
    }
}
