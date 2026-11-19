package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.shared.Money;
import java.util.Currency;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and changes ledger balances, the one mutable table (ledger design, section 5.2). A balance
 * row is inserted with its account, by {@link JdbcAccounts}, and only changed here.
 *
 * <p>Runs in the caller's transaction, like {@link JdbcAccounts}.
 */
@Repository
public class JdbcBalances {

    private final JdbcClient jdbc;

    public JdbcBalances(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Adds {@code delta} to the account's balance, unless that would take a balance below zero that
     * may not go there (invariant I3).
     *
     * <p>The check and the write are one statement, never a read in Java followed by a write.
     * PostgreSQL locks the row, re-reads its current value and evaluates the condition: a concurrent
     * update of the same row waits for this transaction to end, then sees the new balance. So two
     * transactions can never both spend the same money, and there is nothing to retry.
     *
     * @param account the account whose balance changes
     * @param delta the change, signed as in {@code Posting.balanceEffect()}, in the account's currency
     * @return whether the balance was changed; {@code false} means insufficient funds
     */
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

    /**
     * The account's balance, in its currency, if the account exists.
     *
     * <p>A plain read, without a lock: it sees the last committed balance, and a concurrent posting
     * may change it right after.
     */
    public Optional<Money> find(AccountId account) {
        return jdbc.sql("""
                        SELECT b.balance, a.currency
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
