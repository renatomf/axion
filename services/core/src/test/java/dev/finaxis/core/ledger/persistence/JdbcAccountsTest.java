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
import dev.finaxis.core.shared.TenantId;
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
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    JdbcAccountsTest(JdbcAccounts accounts, JdbcClient jdbc) {
        this.accounts = accounts;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void actAsTheLedgerRole() {
        DatabaseRole.LEDGER.actAs(jdbc, new TenantId(tenant));
    }

    @Test
    void insertsAnAccountWithABalanceOfZero() {
        var account = account("tenant:available");

        assertThat(accounts.insertIfAbsent(account)).isTrue();

        assertThat(accounts.findByCode(account.tenantId(), account.code())).contains(account);
        assertThat(storedBalance(jdbc, account.id())).isZero();
    }

    @Test
    void insertsNothingWhenTheTenantAlreadyHasTheCode() {
        var first = account("tenant:available");
        accounts.insertIfAbsent(first);

        assertThat(accounts.insertIfAbsent(account("tenant:available"))).isFalse();

        assertThat(accounts.findByCode(tenant, first.code())).contains(first);
    }

    @Test
    void findsNoAccountForAnUnknownCode() {
        assertThat(accounts.findByCode(tenant, "tenant:unknown")).isEmpty();
    }

    @Test
    void findsAccountsByIdAndLeavesOutUnknownOnes() {
        var first = account("tenant:available");
        var second = account("tenant:held");
        accounts.insertIfAbsent(first);
        accounts.insertIfAbsent(second);
        var unknown = new AccountId(UUID.randomUUID());

        assertThat(accounts.findAllById(List.of(first.id(), second.id(), unknown)))
                .containsOnly(entry(first.id(), first), entry(second.id(), second));
    }

    private Account account(String code) {
        return new Account(new AccountId(UUID.randomUUID()), tenant, code, AccountType.LIABILITY, BRL, false, false);
    }
}
