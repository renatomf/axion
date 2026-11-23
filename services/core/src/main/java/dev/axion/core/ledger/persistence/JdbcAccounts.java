package dev.axion.core.ledger.persistence;

import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.domain.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccounts {

    private final JdbcClient jdbc;

    public JdbcAccounts(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ON CONFLICT DO NOTHING: two transactions opening the same code at once insert it once.
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

    public Optional<Account> findByCode(UUID tenantId, String code) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, type, currency, allow_negative, is_hot
                          FROM ledger.accounts
                         WHERE tenant_id = :tenantId AND code = :code
                        """)
                .param("tenantId", tenantId)
                .param("code", code)
                .query(JdbcAccounts::account)
                .optional();
    }

    /** An ID with no account is absent from the map. */
    public Map<AccountId, Account> findAllById(Collection<AccountId> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jdbc
                .sql("""
                        SELECT id, tenant_id, code, type, currency, allow_negative, is_hot
                          FROM ledger.accounts
                         WHERE id IN (:ids)
                        """)
                .param("ids", ids.stream().map(AccountId::value).toList())
                .query(JdbcAccounts::account)
                .list()
                .stream()
                .collect(Collectors.toMap(Account::id, Function.identity()));
    }

    private static Account account(ResultSet row, int rowNumber) throws SQLException {
        return new Account(
                new AccountId(row.getObject("id", UUID.class)),
                row.getObject("tenant_id", UUID.class),
                row.getString("code"),
                AccountType.valueOf(row.getString("type")),
                Currency.getInstance(row.getString("currency")),
                row.getBoolean("allow_negative"),
                row.getBoolean("is_hot"));
    }
}
