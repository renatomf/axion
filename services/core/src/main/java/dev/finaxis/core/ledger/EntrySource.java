package dev.finaxis.core.ledger;

/**
 * The fact that caused an entry, such as a charge's settlement. One entry per source and purpose
 * (invariant I4) is what makes {@link LedgerApi#post} idempotent.
 *
 * @param purpose why the fact moves money, such as {@code settlement} or {@code payout_hold}
 */
public record EntrySource(String type, String id, String purpose) {}
