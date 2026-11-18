package dev.finaxis.core.ledger;

/**
 * The side of a posting: a debit or a credit. Amounts are always positive; the direction carries
 * the sign (ledger design, section 3.2).
 */
public enum Direction {
    DEBIT,
    CREDIT;

    /** The other side: a reversal posts every leg of the original in the opposite direction. */
    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
