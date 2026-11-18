package dev.finaxis.core.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.domain.Account;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcAccounts} against a real PostgreSQL, as the ledger role, so the tests also prove the
 * role's privileges are enough. Each test runs in a transaction that is rolled back when it ends.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcAccountsTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;

    @Autowired
    JdbcAccountsTest(JdbcAccounts accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    /** Runs inside the test's transaction, so the role lasts until it is rolled back. */
    @BeforeEach
    void actAsTheLedgerRole() {
        jdbc.sql("SET LOCAL ROLE finaxis_ledger").update();
    }

    @Test
    void insertsAnAccountWithABalanceOfZero() {
        var account = available(UUID.randomUUID());

        assertThat(accounts.insertIfAbsent(account)).isTrue();

        assertThat(accounts.findByCode(account.tenantId(), account.code())).contains(account);
        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void insertsNothingWhenTheTenantAlreadyHasTheCode() {
        var tenant = UUID.randomUUID();
        var first = available(tenant);
        accounts.insertIfAbsent(first);

        assertThat(accounts.insertIfAbsent(available(tenant))).isFalse();

        assertThat(accounts.findByCode(tenant, first.code())).contains(first);
    }

    @Test
    void findsNoAccountForAnUnknownCode() {
        assertThat(accounts.findByCode(UUID.randomUUID(), "tenant:unknown:available"))
                .isEmpty();
    }

    private static Account available(UUID tenant) {
        return new Account(
                new AccountId(UUID.randomUUID()),
                tenant,
                "tenant:" + tenant + ":available",
                AccountType.LIABILITY,
                BRL,
                false,
                false);
    }

    private long balanceOf(Account account) {
        return jdbc.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.id().value())
                .query(Long.class)
                .single();
    }
}
