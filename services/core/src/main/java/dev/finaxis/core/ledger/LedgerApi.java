package dev.finaxis.core.ledger;

/**
 * The only way other modules use the ledger (ledger design, section 6). Calls run in the caller's
 * transaction when there is one, so the ledger's writes commit or roll back with the caller's
 * business change.
 *
 * <p>Balances and history join this interface in the next pull requests.
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

    /**
     * Posts a balanced entry, or returns the one its source already produced: posting the same
     * request twice has one effect, for example when the caller retries after a timeout.
     *
     * <p>The entry updates the balance of every account it touches, except hot ones, in the same
     * transaction. If a balance that may not go negative would, nothing is posted and the caller's
     * own work in the transaction is kept: the caller decides what to do.
     *
     * <p>The ledger checks that the entry balances again when the transaction commits; if it does
     * not, the whole transaction rolls back.
     *
     * @return {@link PostResult.Posted} if this call posted the entry, {@link
     *     PostResult.AlreadyPosted} if the source had already been posted with the same postings,
     *     {@link PostResult.InsufficientFunds} if a guarded balance was short
     * @throws IllegalArgumentException if the request breaks the ledger's rules (fewer than two or
     *     more than 32 postings, unbalanced, an amount that is not positive, an unknown account, or
     *     a currency other than the account's), which is a bug in the caller
     * @throws IllegalStateException if the source was already posted for another tenant or with
     *     other postings, which is a bug in the caller
     */
    PostResult post(EntryRequest request);

    /**
     * The account's current balance: the last committed one, read without waiting for postings in
     * progress.
     *
     * @throws IllegalArgumentException if there is no account with that ID, which is a bug in the
     *     caller: account IDs come from {@link #openAccount}
     */
    Balance balanceOf(AccountId account);

    /**
     * A page of the account's postings, newest first: ordered by descending posting ID, which is a
     * UUIDv7 and so orders them by creation time to the millisecond. Postings created in the same
     * millisecond come in a fixed but arbitrary order.
     *
     * <p>Start with {@link Cursor#first()} and pass each page's {@link Page#next()} to read the
     * following one.
     *
     * @param limit how many postings at most, from 1 to {@value #MAX_HISTORY_LIMIT}
     * @throws IllegalArgumentException if the limit is out of range or there is no account with that
     *     ID, which is a bug in the caller
     */
    Page<PostingView> history(AccountId account, Cursor cursor, int limit);

    /** The largest page {@link #history} returns. */
    int MAX_HISTORY_LIMIT = 100;
}
