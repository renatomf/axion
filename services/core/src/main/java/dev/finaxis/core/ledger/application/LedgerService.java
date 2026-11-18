package dev.finaxis.core.ledger.application;

import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.domain.Account;
import dev.finaxis.core.ledger.persistence.JdbcAccounts;
import dev.finaxis.core.shared.UuidV7Generator;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger's implementation of {@link LedgerApi}. Other modules depend on the interface; Spring
 * passes them this bean, the only implementation.
 */
@Service
public class LedgerService implements LedgerApi {

    private final JdbcAccounts accounts;
    private final UuidV7Generator ids;

    public LedgerService(JdbcAccounts accounts, Clock clock) {
        this.accounts = accounts;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional} joins the caller's transaction, or starts one if there is none, so
     * the account and its balance row are inserted together or not at all.
     */
    @Override
    @Transactional
    public AccountId openAccount(AccountRequest request) {
        // The constructor checks the request's rules before anything is written.
        var account = new Account(
                new AccountId(ids.next()),
                request.tenantId(),
                request.code(),
                request.type(),
                request.currency(),
                request.allowNegative(),
                request.hot());
        if (accounts.insertIfAbsent(account)) {
            return account.id();
        }
        // The code was taken. Accounts are never deleted, so the existing one is there to read.
        var existing = accounts.findByCode(request.tenantId(), request.code()).orElseThrow();
        if (!termsOf(existing).equals(request)) {
            throw new IllegalStateException(
                    "Account " + request.code() + " is already open with other terms: " + termsOf(existing));
        }
        return existing.id();
    }

    private static AccountRequest termsOf(Account account) {
        return new AccountRequest(
                account.tenantId(),
                account.code(),
                account.type(),
                account.currency(),
                account.allowNegative(),
                account.hot());
    }
}
