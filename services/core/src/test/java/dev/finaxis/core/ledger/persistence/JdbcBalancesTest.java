package dev.finaxis.core.ledger.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
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
 * {@link JdbcBalances} against a real PostgreSQL, as the ledger role. Each test runs in a
 * transaction that is rolled back when it ends.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcBalancesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final JdbcBalances balances;
    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;
    private final UuidV7Generator ids = new UuidV7Generator(Clock.systemUTC());

    @Autowired
    JdbcBalancesTest(JdbcBalances balances, JdbcAccounts accounts, JdbcClient jdbc) {
        this.balances = balances;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        jdbc.sql("SET LOCAL ROLE finaxis_ledger").update();
    }

    @Test
    void addsTheDeltaAndCountsTheChange() {
        var account = open(false);

        assertThat(balances.apply(account, brl(1_000))).isTrue();
        assertThat(balances.apply(account, brl(-400))).isTrue();

        assertThat(balanceOf(account)).isEqualTo(600);
        assertThat(versionOf(account)).isEqualTo(2);
    }

    @Test
    void spendsTheWholeBalance() {
        var account = open(false);
        balances.apply(account, brl(1_000));

        assertThat(balances.apply(account, brl(-1_000))).isTrue();

        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void refusesToTakeAGuardedBalanceBelowZero() {
        var account = open(false);
        balances.apply(account, brl(1_000));

        assertThat(balances.apply(account, brl(-1_001))).isFalse();

        assertThat(balanceOf(account)).isEqualTo(1_000);
        assertThat(versionOf(account)).isOne();
    }

    @Test
    void takesAnOverdraftTolerantBalanceBelowZero() {
        var account = open(true);

        assertThat(balances.apply(account, brl(-1_000))).isTrue();

        assertThat(balanceOf(account)).isEqualTo(-1_000);
    }

    private Account open(boolean allowNegative) {
        var account = new Account(
                new AccountId(ids.next()),
                UUID.randomUUID(),
                "tenant:available",
                AccountType.LIABILITY,
                BRL,
                allowNegative,
                false);
        accounts.insertIfAbsent(account);
        return account;
    }

    private static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, BRL);
    }

    private long balanceOf(Account account) {
        return jdbc.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.id().value())
                .query(Long.class)
                .single();
    }

    private long versionOf(Account account) {
        return jdbc.sql("SELECT version FROM ledger.balances WHERE account_id = ?")
                .param(account.id().value())
                .query(Long.class)
                .single();
    }
}
