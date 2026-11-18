package dev.finaxis.core.ledger;

/**
 * The fact that caused an entry, such as the settlement of a charge (ledger design, section 3.3). A
 * source produces at most one entry per purpose (invariant I4), which is what makes {@link
 * LedgerApi#post} idempotent.
 *
 * @param type the kind of fact, such as {@code charge}, {@code payout} or {@code refund}
 * @param id the fact's ID, at most 64 characters
 * @param purpose why the fact moves money, such as {@code settlement} or {@code payout_hold}
 */
public record EntrySource(String type, String id, String purpose) {}
