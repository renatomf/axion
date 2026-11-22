package dev.finaxis.core.billing;

/** Where a charge is in its lifecycle (billing design, section 3.2). */
public enum ChargeStatus {
    ACTIVE,
    /** Its expiry passed unpaid; a payment accepted before the expiry still pays it. */
    EXPIRED
}
