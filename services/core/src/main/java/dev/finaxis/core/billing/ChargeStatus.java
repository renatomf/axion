package dev.finaxis.core.billing;

/** Where a charge is in its lifecycle (billing design, section 3.2). */
public enum ChargeStatus {
    ACTIVE,
    /** Its expiry passed unpaid; a payment accepted before the expiry still pays it. */
    EXPIRED,
    /** Settled by a payment of its amount, accepted before its expiry. */
    PAID,
    /** A refund returned part of its settling payment. */
    PARTIALLY_REFUNDED,
    /** Refunds returned all of its settling payment. Final. */
    REFUNDED;

    /** Settled by a payment, whatever refunds followed. */
    public boolean isPaid() {
        return this == PAID || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }
}
