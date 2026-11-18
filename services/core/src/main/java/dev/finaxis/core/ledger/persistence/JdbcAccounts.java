package dev.finaxis.core.ledger.persistence;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.domain.Account;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and inserts ledger accounts (ledger design, section 3.3). Accounts are never updated, so
 * there is no update method.
 *
 * <p>Runs in the caller's transaction: {@code JdbcClient} uses the connection Spring has bound to
 * the current transaction, if there is one.
 */
@Repository
public class JdbcAccounts {

    private final JdbcClient jdbc;

    public JdbcAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the account and its balance row, at zero, unless the tenant already has an account
     * with the same code.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes the check and the insert one atomic statement: when
     * two transactions open the same code at once, the second waits for the first to finish, then
     * inserts nothing.
     *
     * @return whether the account was inserted; {@code false} if the code was taken
     */
    public boolean insertIfAbsent(Account account) {
        int inserted = jdbc.sql("""
                        INSERT INTO ledger.accounts
                            (id, tenant_id, code, type, normal_balance, currency, allow_negative, is_hot)
                        VALUES (:id, :tenantId, :code, :type, :normalBalance, :currency, :allowNegative, :hot)
                        ON CONFLICT (tenant_id, code) DO NOTHING
                        """)
                .param("id", account.id().value())
                .param("tenantId", account.tenantId())
                .param("code", account.code())
                .param("type", account.type().name())
                .param("normalBalance", account.type().normalBalance().name())
                .param("currency", account.currency().getCurrencyCode())
                .param("allowNegative", account.allowNegative())
                .param("hot", account.hot())
                .update();
        if (inserted == 0) {
            return false;
        }
        jdbc.sql("INSERT INTO ledger.balances (account_id) VALUES (:id)")
                .param("id", account.id().value())
                .update();
        return true;
    }

    /** The tenant's account with the given code, if it has one. */
    public Optional<Account> findByCode(UUID tenantId, String code) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, type, currency, allow_negative, is_hot
                          FROM ledger.accounts
                         WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query((row, rowNumber) -> new Account(
                        new AccountId(row.getObject("id", UUID.class)),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("code"),
                        AccountType.valueOf(row.getString("type")),
                        Currency.getInstance(row.getString("currency")),
                        row.getBoolean("allow_negative"),
                        row.getBoolean("is_hot")))
                .optional();
    }
}
