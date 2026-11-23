package dev.axion.core.ledger.persistence;

import static dev.axion.core.ledger.LedgerFixtures.BRL;
import static dev.axion.core.ledger.LedgerFixtures.brl;
import static dev.axion.core.ledger.LedgerFixtures.storedBalance;
import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.domain.Account;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** {@link JdbcBalances} as the ledger role. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcBalancesTest {

    private final JdbcBalances balances;
    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UuidV7Generator ids = new UuidV7Generator(Clock.systemUTC());

    @Autowired
    JdbcBalancesTest(JdbcBalances balances, JdbcAccounts accounts, JdbcClient jdbc) {
        this.balances = balances;
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
    }

    @Test
    void addsTheDeltaAndCountsTheChange() {
        var account = open(false);

        assertThat(balances.apply(account, brl(1_000))).isTrue();
        assertThat(balances.apply(account, brl(-400))).isTrue();

        assertThat(storedBalance(jdbc, account.id())).isEqualTo(600);
        assertThat(versionOf(account)).isEqualTo(2);
    }

    @Test
    void spendsTheWholeBalance() {
        var account = open(false);
        balances.apply(account, brl(1_000));

        assertThat(balances.apply(account, brl(-1_000))).isTrue();

        assertThat(storedBalance(jdbc, account.id())).isZero();
    }

    @Test
    void refusesToTakeAGuardedBalanceBelowZero() {
        var account = open(false);
        balances.apply(account, brl(1_000));

        assertThat(balances.apply(account, brl(-1_001))).isFalse();

        assertThat(storedBalance(jdbc, account.id())).isEqualTo(1_000);
        assertThat(versionOf(account)).isOne();
    }

    @Test
    void takesAnOverdraftTolerantBalanceBelowZero() {
        var account = open(true);

        assertThat(balances.apply(account, brl(-1_000))).isTrue();

        assertThat(storedBalance(jdbc, account.id())).isEqualTo(-1_000);
    }

    private Account open(boolean allowNegative) {
        var account = new Account(
                new AccountId(ids.next()),
                tenant,
                "tenant:available",
                AccountType.LIABILITY,
                BRL,
                allowNegative,
                false);
        accounts.insertIfAbsent(account);
        return account;
    }

    private long versionOf(Account account) {
        return jdbc.sql("SELECT version FROM ledger.balances WHERE account_id = ?")
                .param(account.id().value())
                .query(Long.class)
                .single();
    }
}
