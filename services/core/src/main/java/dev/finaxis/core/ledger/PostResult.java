package dev.finaxis.core.ledger;

/**
 * The outcome of {@code LedgerApi.post}. Expected outcomes are values, not exceptions (overview
 * design, section 19.1), and the interface is sealed, so a {@code switch} over it must handle each
 * one.
 *
 * <p>{@code InsufficientFunds} joins these with the balance checks (roadmap, PR 1.11).
 */
public sealed interface PostResult {

    /** The ID of the entry the request produced, now or before. */
    EntryId entry();

    /** The entry was posted by this call. */
    record Posted(EntryId entry) implements PostResult {}

    /** The source had already been posted with the same postings, so nothing changed. */
    record AlreadyPosted(EntryId entry) implements PostResult {}
}
