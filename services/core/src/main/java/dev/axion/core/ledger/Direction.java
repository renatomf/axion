package dev.axion.core.ledger;

/** The side of a posting. Amounts are always positive; the direction carries the sign. */
public enum Direction {
    DEBIT,
    CREDIT;

    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
