package dev.finaxis.core.ledger;

/**
 * The outcome of {@link LedgerApi#post}. Expected outcomes are values, not exceptions (overview
 * design, section 19.1), and the interface is sealed, so a {@code switch} over it must handle each
 * one.
 */
public sealed interface PostResult {

    /** The entry was posted by this call. */
    record Posted(EntryId entry) implements PostResult {}

    /** The source had already been posted with the same postings, so nothing changed. */
    record AlreadyPosted(EntryId entry) implements PostResult {}

    /**
     * The entry would take the account's balance below zero, and the account may not go there.
     * Nothing was posted; the caller decides what to do, such as refusing a payout.
     *
     * @param account the first account, in ID order, whose balance was short
     */
    record InsufficientFunds(AccountId account) implements PostResult {}
}
