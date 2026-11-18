package dev.finaxis.core.ledger;

/**
 * The only way other modules use the ledger (ledger design, section 6). Calls run in the caller's
 * transaction when there is one, so the ledger's writes commit or roll back with the caller's
 * business change.
 *
 * <p>Posting entries, balances and history join this interface in the next pull requests.
 */
public interface LedgerApi {

    /**
     * Opens an account with its balance at zero, or returns the existing one: opening the same
     * account twice is safe, for example when the caller retries after a timeout.
     *
     * @return the ID of the tenant's account with the request's code
     * @throws IllegalStateException if the tenant already has an account with that code and other
     *     terms (type, currency, overdraft rule or hot flag), which is a bug in the caller
     */
    AccountId openAccount(AccountRequest request);
}
