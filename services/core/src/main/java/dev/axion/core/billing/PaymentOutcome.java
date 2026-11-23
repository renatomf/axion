package dev.axion.core.billing;

/** What a payment did to its charge (billing design, section 3.3). Either way, it is credited to the tenant. */
public enum PaymentOutcome {
    /** The charge's one settling payment. */
    SETTLED,
    /** Credited without settling the charge, for a {@link ReviewReason}; the tenant decides whether to refund it. */
    FLAGGED
}
