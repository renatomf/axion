package dev.finaxis.core.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import java.util.Collections;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link LedgerApi#openAccount}. Each call commits, so every test uses a new tenant. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class OpenAccountTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    private final LedgerApi ledger;
    private final JdbcClient jdbc;

    @Autowired
    OpenAccountTest(LedgerApi ledger, JdbcClient jdbc) {
        this.ledger = ledger;
        this.jdbc = jdbc;
    }

    @Test
    void opensAnAccountWithABalanceOfZero() {
        var account = ledger.openAccount(available(UUID.randomUUID(), BRL));

        assertThat(balanceOf(account)).isZero();
    }

    @Test
    void returnsTheSameAccountWhenOpenedTwice() {
        var request = available(UUID.randomUUID(), BRL);

        assertThat(ledger.openAccount(request)).isEqualTo(ledger.openAccount(request));
    }

    @Test
    void opensAnotherAccountForAnotherTenantWithTheSameCode() {
        var first = available(UUID.randomUUID(), BRL);
        var second = new AccountRequest(UUID.randomUUID(), first.code(), first.type(), BRL, false, false);

        assertThat(ledger.openAccount(first)).isNotEqualTo(ledger.openAccount(second));
    }

    @Test
    void rejectsTheSameCodeWithOtherTerms() {
        var tenant = UUID.randomUUID();
        ledger.openAccount(available(tenant, BRL));

        assertThatThrownBy(() -> ledger.openAccount(available(tenant, USD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open with other terms");
    }

    @Test
    void rejectsAHotAccountThatForbidsANegativeBalance() {
        var tenant = UUID.randomUUID();
        var request = new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, false, true);

        assertThatThrownBy(() -> ledger.openAccount(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accountsOf(tenant)).isZero();
    }

    @Test
    void opensOneAccountWhenManyCallersOpenItAtOnce() throws InterruptedException, ExecutionException {
        var request = available(UUID.randomUUID(), BRL);
        Callable<AccountId> open = () -> ledger.openAccount(request);

        // Ten threads, each with its own connection and transaction, open the same account.
        try (var threads = Executors.newFixedThreadPool(10)) {
            var results = threads.invokeAll(Collections.nCopies(10, open));
            var first = results.getFirst().get();
            for (Future<AccountId> result : results) {
                assertThat(result.get()).isEqualTo(first);
            }
        }
        assertThat(accountsOf(request.tenantId())).isOne();
    }

    private static AccountRequest available(UUID tenant, Currency currency) {
        return new AccountRequest(
                tenant, "tenant:" + tenant + ":available", AccountType.LIABILITY, currency, false, false);
    }

    private long balanceOf(AccountId account) {
        return jdbc.sql("SELECT balance FROM ledger.balances WHERE account_id = ?")
                .param(account.value())
                .query(Long.class)
                .single();
    }

    private long accountsOf(UUID tenant) {
        return jdbc.sql("SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single();
    }
}
