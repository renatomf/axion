package dev.finaxis.core.ledger.persistence;

import static dev.finaxis.core.ledger.LedgerFixtures.BRL;
import static dev.finaxis.core.ledger.LedgerFixtures.storedBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.domain.Account;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** {@link JdbcAccounts} as the ledger role, so the tests also prove its privileges are enough. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcAccountsTest {

    private final JdbcAccounts accounts;
    private final JdbcClient jdbc;

    @Autowired
    JdbcAccountsTest(JdbcAccounts accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        DatabaseRole.LEDGER.actAs(jdbc);
    }

    @Test
    void insertsAnAccountWithABalanceOfZero() {
        var account = available(UUID.randomUUID());

        assertThat(accounts.insertIfAbsent(account)).isTrue();

        assertThat(accounts.findByCode(account.tenantId(), account.code())).contains(account);
        assertThat(storedBalance(jdbc, account.id())).isZero();
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

    @Test
    void findsAccountsByIdAndLeavesOutUnknownOnes() {
        var first = available(UUID.randomUUID());
        var second = available(UUID.randomUUID());
        accounts.insertIfAbsent(first);
        accounts.insertIfAbsent(second);
        var unknown = new AccountId(UUID.randomUUID());

        assertThat(accounts.findAllById(List.of(first.id(), second.id(), unknown)))
                .containsOnly(entry(first.id(), first), entry(second.id(), second));
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
}
