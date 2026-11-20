package dev.finaxis.core.ledger;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Calls {@link LedgerApi} as its callers do: with one tenant of the test environment bound, so its
 * transactions act for that tenant under Row-Level Security.
 */
public final class TenantBoundLedger implements LedgerApi {

    private final LedgerApi ledger;
    private final TenantId tenant;

    public TenantBoundLedger(LedgerApi ledger, UUID tenant) {
        this.ledger = ledger;
        this.tenant = new TenantId(tenant);
    }

    /** Runs the work with the tenant bound, for a test that opens the transaction itself. */
    public void run(Runnable work) {
        TenantContext.run(tenant, Environment.TEST, work);
    }

    public <T> T call(Supplier<T> work) {
        return TenantContext.call(tenant, Environment.TEST, work::get);
    }

    @Override
    public AccountId openAccount(AccountRequest request) {
        return call(() -> ledger.openAccount(request));
    }

    @Override
    public PostResult post(EntryRequest request) {
        return call(() -> ledger.post(request));
    }

    @Override
    public Balance balanceOf(AccountId account) {
        return call(() -> ledger.balanceOf(account));
    }

    @Override
    public Page<PostingView> history(AccountId account, Cursor cursor, int limit) {
        return call(() -> ledger.history(account, cursor, limit));
    }
}
