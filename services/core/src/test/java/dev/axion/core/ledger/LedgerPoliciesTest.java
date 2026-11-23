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
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Row-Level Security on the ledger's tables (tenancy design, section 5.5). The owner arranges the
 * rows, then the ledger's role reads and writes them for one tenant; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerPoliciesTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID platform = UUID.randomUUID();

    @Autowired
    LedgerPoliciesTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void showsTheLedgerRoleNothingWithNoTenantBound() {
        var account = insertAccount(tenant);
        var entry = insertEntry(tenant);
        insertPosting(entry, account, tenant);
        DatabaseRole.LEDGER.actAs(jdbc);

        assertThat(visibleRows(account, entry)).isZero();
    }

    @Test
    void hidesAnotherTenantsLedgerFromTheLedgerRole() {
        var account = insertAccount(other);
        var entry = insertEntry(other);
        insertPosting(entry, account, other);
        actForTheTenant();

        var updated = jdbc.sql("UPDATE ledger.balances SET balance = 100 WHERE account_id = ?")
                .param(account)
                .update();

        assertThat(visibleRows(account, entry)).isZero();
        assertThat(updated).isZero();
    }

    @Test
    void showsATenantThePlatformLegsOfItsOwnEntriesOnly() {
        var fees = insertAccount(platform);
        var own = insertEntry(tenant);
        insertPosting(own, insertAccount(tenant), tenant);
        insertPosting(own, fees, platform);
        var others = insertEntry(other);
        insertPosting(others, insertAccount(other), other);
        insertPosting(others, fees, platform);
        actForTheTenant();

        assertThat(count("SELECT count(*) FROM ledger.accounts WHERE id = ?", fees))
                .isOne();
        assertThat(count("SELECT count(*) FROM ledger.postings WHERE account_id = ?", fees))
                .isOne();
        assertThat(count("SELECT count(*) FROM ledger.postings WHERE entry_id = ?", own))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ledger.balances WHERE account_id = ?", fees))
                .isZero();
    }

    @Test
    void letsATenantPostItsOwnEntryToThePlatformsAccounts() {
        var fees = insertAccount(platform);
        var available = insertAccount(tenant);
        actForTheTenant();

        var entry = insertEntry(tenant);
        insertPosting(entry, available, tenant);
        insertPosting(entry, fees, platform);

        assertThat(count("SELECT count(*) FROM ledger.postings WHERE entry_id = ?", entry))
                .isEqualTo(2);
    }

    // A denied statement aborts the transaction, so each test tries one.
    @Test
    void deniesATenantOpeningAnAccountForAnotherTenant() {
        actForTheTenant();

        assertThatThrownBy(() -> insertAccount(other)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesATenantOpeningAnAccountForThePlatform() {
        actForTheTenant();

        assertThatThrownBy(() -> insertAccount(platform)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesATenantPostingAnEntryForAnotherTenant() {
        actForTheTenant();

        assertThatThrownBy(() -> insertEntry(other)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesATenantPostingToAnotherTenantsAccount() {
        var othersAccount = insertAccount(other);
        actForTheTenant();
        var own = insertEntry(tenant);

        assertThatThrownBy(() -> insertPosting(own, othersAccount, other))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesATenantAddingALegToAnotherTenantsEntry() {
        var othersEntry = insertEntry(other);
        var fees = insertAccount(platform);
        actForTheTenant();

        assertThatThrownBy(() -> insertPosting(othersEntry, fees, platform))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    private void actForTheTenant() {
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant), new TenantId(platform));
    }

    private long visibleRows(UUID account, UUID entry) {
        return count("SELECT count(*) FROM ledger.accounts WHERE id = ?", account)
                + count("SELECT count(*) FROM ledger.balances WHERE account_id = ?", account)
                + count("SELECT count(*) FROM ledger.entries WHERE id = ?", entry)
                + count("SELECT count(*) FROM ledger.postings WHERE entry_id = ?", entry);
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param(id).query(Long.class).single();
    }

    /** An account with its balance row, as the ledger opens one. */
    private UUID insertAccount(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger.accounts
                            (id, tenant_id, code, type, normal_balance, currency, allow_negative, is_hot)
                        VALUES (?, ?, ?, 'LIABILITY', 'CREDIT', 'BRL', false, false)
                        """).params(id, owner, "tenant:" + id).update();
        jdbc.sql("INSERT INTO ledger.balances (account_id) VALUES (?)")
                .param(id)
                .update();
        return id;
    }

    private UUID insertEntry(UUID owner) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger.entries
                            (id, tenant_id, source_type, source_id, purpose, effective_date, correlation_id)
                        VALUES (?, ?, 'test', ?, 'test', DATE '2026-11-20', 'test')
                        """).params(id, owner, id.toString()).update();
        return id;
    }

    private void insertPosting(UUID entry, UUID account, UUID owner) {
        jdbc.sql("""
                        INSERT INTO ledger.postings (id, entry_id, account_id, tenant_id, currency, direction, amount)
                        VALUES (?, ?, ?, ?, 'BRL', 'CREDIT', 100)
                        """).params(UUID.randomUUID(), entry, account, owner).update();
    }
}
