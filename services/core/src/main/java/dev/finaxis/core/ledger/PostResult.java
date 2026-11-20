package dev.finaxis.core.ledger;

/** The outcome of {@link LedgerApi#post}. */
public sealed interface PostResult {

    record Posted(EntryId entry) implements PostResult {}

    /** The source was already posted with the same postings; nothing changed. */
    record AlreadyPosted(EntryId entry) implements PostResult {}

    /** Nothing was posted; {@code account} is the first short account in ID order. */
    record InsufficientFunds(AccountId account) implements PostResult {}
}
