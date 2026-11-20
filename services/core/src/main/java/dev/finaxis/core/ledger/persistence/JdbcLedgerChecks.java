package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// Full scans that report and repair nothing; one REPEATABLE READ transaction runs all three.
@Repository
public class JdbcLedgerChecks {

    private final JdbcClient jdbc;

    public JdbcLedgerChecks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** I1: entries with fewer than two postings, or unequal debits and credits in a currency. */
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
     * I6: accounts whose balance differs from the sum of their postings, a hot one counting only what
     * was folded. An account without a balance row is reported too.
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
