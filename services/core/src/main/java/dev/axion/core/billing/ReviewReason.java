package dev.axion.core.billing;

/** Why a payment was flagged instead of settling its charge (billing design, section 8.2). */
public enum ReviewReason {
    /** Another payment had already settled the charge. */
    CHARGE_ALREADY_PAID,
    /** The rails accepted it at or after the charge's expiry. */
    CHARGE_EXPIRED,
    /** Its amount differs from the charge's. */
    AMOUNT_MISMATCH
}
