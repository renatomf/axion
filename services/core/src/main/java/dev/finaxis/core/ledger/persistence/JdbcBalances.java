package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.shared.Money;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// The one mutable table: a balance row is inserted with its account and changed only here.
@Repository
public class JdbcBalances {

    private final JdbcClient jdbc;

    public JdbcBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Invariant I3 as one statement, never a read then a write: PostgreSQL locks the row and re-reads
    // it, so two transactions can never both spend the same money, and there is nothing to retry.
    public boolean apply(Account account, Money delta) {
        int updated = jdbc.sql("""
                        UPDATE ledger.balances
                           SET balance = balance + :delta, version = version + 1, updated_at = now()
                         WHERE account_id = :accountId
                           AND (:allowNegative OR balance + :delta >= 0)
                        """)
                .param("delta", delta.minorUnits())
                .param("accountId", account.id().value())
                .param("allowNegative", account.allowNegative())
                .update();
        return updated == 1;
    }

    // No lock: the last committed balance. A hot account's unfolded postings are added in the same
    // statement, so both come from one snapshot (section 5.3).
    public Optional<Money> find(AccountId account) {
        return jdbc.sql("""
                        SELECT a.currency,
                               b.balance + coalesce((
                                   SELECT sum(CASE WHEN p.direction = a.normal_balance
                                                   THEN p.amount ELSE -p.amount END)
                                     FROM ledger.postings p
                                    WHERE a.is_hot
                                      AND p.account_id = a.id
                                      AND (b.folded_until IS NULL OR p.created_at >= b.folded_until)
                               ), 0) AS balance
                          FROM ledger.accounts a
                          JOIN ledger.balances b ON b.account_id = a.id
                         WHERE a.id = :accountId
                        """)
                .param("accountId", account.value())
                .query((row, rowNumber) ->
                        Money.ofMinor(row.getLong("balance"), Currency.getInstance(row.getString("currency"))))
                .optional();
    }
}
