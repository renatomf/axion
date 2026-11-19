package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Recomputes the ledger from its postings and returns what disagrees (ledger design, section 7).
 * Each method scans every row it checks and finds violations only; it repairs nothing.
 *
 * <p>Runs in the caller's transaction. Called from one {@code REPEATABLE READ} transaction, all
 * three see the same snapshot, so a posting committed between two of them cannot make them
 * disagree.
 */
@Repository
public class JdbcLedgerChecks {

    private final JdbcClient jdbc;

    public JdbcLedgerChecks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * I1: the entries with fewer than two postings, or whose debits and credits differ in some
     * currency.
     */
    public List<EntryId> unbalancedEntries() {
        return jdbc.sql("""
                        SELECT e.id
                          FROM ledger.entries e
                          LEFT JOIN ledger.postings p ON p.entry_id = e.id
                         GROUP BY e.id
                        HAVING count(p.id) < 2
                        UNION
                        SELECT entry_id
                          FROM ledger.postings
                         GROUP BY entry_id, currency
                        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                        """)
                .query((row, number) -> new EntryId(row.getObject("id", UUID.class)))
                .list();
    }

    /**
     * I6: the accounts whose balance differs from the sum of their postings, signed by the account's
     * normal balance (section 3.2). A hot account's balance holds only the postings folded into it,
     * those created before {@code folded_until} (section 5.3). An account without a balance row is
     * reported too: its balance is null, which is distinct from any sum.
     */
    public List<AccountId> driftedBalances() {
        return jdbc.sql("""
                        SELECT a.id
                          FROM ledger.accounts a
                          LEFT JOIN ledger.balances b ON b.account_id = a.id
                          LEFT JOIN ledger.postings p ON p.account_id = a.id
                               AND (NOT a.is_hot OR p.created_at < b.folded_until)
                         GROUP BY a.id, b.account_id
                        HAVING b.balance IS DISTINCT FROM coalesce(
                                   sum(CASE WHEN p.direction = a.normal_balance THEN p.amount ELSE -p.amount END), 0)
                        """)
                .query((row, number) -> new AccountId(row.getObject("id", UUID.class)))
                .list();
    }

    /** I7: the currencies in which the debits of all postings differ from their credits. */
    public List<Currency> unbalancedCurrencies() {
        return jdbc.sql("""
                        SELECT currency
                          FROM ledger.postings
                         GROUP BY currency
                        HAVING sum(CASE direction WHEN 'DEBIT' THEN amount ELSE -amount END) <> 0
                        """)
                .query((row, number) -> Currency.getInstance(row.getString("currency")))
                .list();
    }
}
