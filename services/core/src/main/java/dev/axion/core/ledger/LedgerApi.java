package dev.axion.core.ledger;

/**
 * The only way other modules use the ledger (ledger design, section 6). Calls join the caller's
 * transaction, so the ledger's writes commit or roll back with the caller's business change.
 */
public interface LedgerApi {

    /**
     * Opens an account at zero, or returns the existing one, so a retry is safe.
     *
     * @throws IllegalStateException if the code exists with other terms, a bug in the caller
     */
    AccountId openAccount(AccountRequest request);

    /**
     * Posts a balanced entry, or returns the one its source already produced, so a retry has one
     * effect. Balances, except hot ones, change in the same transaction. On insufficient funds
     * nothing is posted and the caller's own work is kept. The database checks the balance again at
     * commit.
     *
     * @throws IllegalArgumentException if the request breaks the ledger's rules, a bug in the caller
     * @throws IllegalStateException if the source was posted with other postings, a bug in the caller
     */
    PostResult post(EntryRequest request);

    /**
     * The last committed balance, read without waiting for postings in progress.
     *
     * @throws IllegalArgumentException for an unknown account, a bug in the caller
     */
    Balance balanceOf(AccountId account);

    /**
     * The account's postings, newest first by UUIDv7 ID; within one millisecond the order is fixed
     * but arbitrary. Start from {@link Cursor#first()}, then each page's {@link Page#next()}.
     *
     * @param limit 1 to {@value #MAX_HISTORY_LIMIT}
     */
    Page<PostingView> history(AccountId account, Cursor cursor, int limit);

    int MAX_HISTORY_LIMIT = 100;
}
