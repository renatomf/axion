package dev.finaxis.core.ledger;

import static dev.finaxis.core.ledger.LedgerFixtures.BRL;
import static dev.finaxis.core.ledger.LedgerFixtures.storedBalance;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
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

    private static final Currency USD = Currency.getInstance("USD");

    private final LedgerApi api;
    private final JdbcClient owner;

    @Autowired
    OpenAccountTest(LedgerApi api, DatabaseOwner databaseOwner) {
        this.api = api;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void opensAnAccountWithABalanceOfZero() {
        var account = open(available(UUID.randomUUID(), BRL));

        assertThat(storedBalance(owner, account)).isZero();
    }

    @Test
    void returnsTheSameAccountWhenOpenedTwice() {
        var request = available(UUID.randomUUID(), BRL);

        assertThat(open(request)).isEqualTo(open(request));
    }

    @Test
    void opensAnotherAccountForAnotherTenantWithTheSameCode() {
        var first = available(UUID.randomUUID(), BRL);
        var second = new AccountRequest(UUID.randomUUID(), first.code(), first.type(), BRL, false, false);

        assertThat(open(first)).isNotEqualTo(open(second));
    }

    @Test
    void rejectsTheSameCodeWithOtherTerms() {
        var tenant = UUID.randomUUID();
        open(available(tenant, BRL));

        assertThatThrownBy(() -> open(available(tenant, USD)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already open with other terms");
    }

    @Test
    void rejectsAHotAccountThatForbidsANegativeBalance() {
        var tenant = UUID.randomUUID();
        var request = new AccountRequest(tenant, "platform:settlement", AccountType.ASSET, BRL, false, true);

        assertThatThrownBy(() -> open(request)).isInstanceOf(IllegalArgumentException.class);
        assertThat(accountsOf(tenant)).isZero();
    }

    @Test
    void opensOneAccountWhenManyCallersOpenItAtOnce() throws InterruptedException, ExecutionException {
        var request = available(UUID.randomUUID(), BRL);
        Callable<AccountId> opening = () -> open(request);

        // Ten threads, each with its own connection and transaction, open the same account.
        try (var threads = Executors.newFixedThreadPool(10)) {
            var results = threads.invokeAll(Collections.nCopies(10, opening));
            var first = results.getFirst().get();
            for (Future<AccountId> result : results) {
                assertThat(result.get()).isEqualTo(first);
            }
        }
        assertThat(accountsOf(request.tenantId())).isOne();
    }

    /** Opens the account as its tenant's callers do, with that tenant bound. */
    private AccountId open(AccountRequest request) {
        return new TenantBoundLedger(api, request.tenantId()).openAccount(request);
    }

    private static AccountRequest available(UUID tenant, Currency currency) {
        return new AccountRequest(
                tenant, "tenant:" + tenant + ":available", AccountType.LIABILITY, currency, false, false);
    }

    private long accountsOf(UUID tenant) {
        return owner.sql("SELECT count(*) FROM ledger.accounts WHERE tenant_id = ?")
                .param(tenant)
                .query(Long.class)
                .single();
    }
}
