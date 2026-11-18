package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's tables and the rules the database enforces on them (ledger design, sections 3.3 and
 * 4), checked against a real PostgreSQL.
 *
 * <p>Tests annotated with {@code @Transactional} run inside one transaction that Spring's test
 * support rolls back when the test ends, so they leave no rows behind.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class LedgerSchemaTest {

    private final JdbcClient jdbc;

    @Autowired
    LedgerSchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    @Transactional
    void rejectsAnAccountWhoseNormalBalanceContradictsItsType() {
        assertThatThrownBy(() -> insertAccount(UUID.randomUUID(), "ASSET", "CREDIT", "BRL"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAPostingInAnotherCurrencyThanItsAccount() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        var entry = insertEntry(tenant);

        assertThatThrownBy(() -> insertPosting(entry, account, tenant, "USD", "CREDIT", 100))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsAPostingWithoutAPositiveAmount() {
        var tenant = UUID.randomUUID();
        var account = insertAccount(tenant, "LIABILITY", "CREDIT", "BRL");
        var entry = insertEntry(tenant);

        assertThatThrownBy(() -> insertPosting(entry, account, tenant, "BRL", "CREDIT", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
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
    @Transactional
    void rejectsDeletingAnEntryEvenAsTheOwner() {
        var entry = insertEntryWithOnePosting();

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM ledger.entries WHERE id = ?")
                        .param(entry)
                        .update())
                .hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void rejectsTruncatingPostings() {
        assertThatThrownBy(() -> jdbc.sql("TRUNCATE ledger.postings").update()).hasMessageContaining("append-only");
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

    private UUID insertEntry(UUID tenant) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO ledger.entries
                            (id, tenant_id, source_type, source_id, purpose, effective_date, correlation_id)
                        VALUES (?, ?, 'test', ?, 'test', DATE '2026-11-18', 'test')
                        """).params(id, tenant, id.toString()).update();
        return id;
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
