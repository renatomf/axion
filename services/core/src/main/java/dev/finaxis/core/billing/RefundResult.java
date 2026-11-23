package dev.finaxis.core.billing;

/** The outcome of {@link BillingApi#refund}. */
public sealed interface RefundResult {

    /** The refund, posted and recorded. */
    record Refunded(RefundView refund) implements RefundResult {}

    /** A rule of section 10.2 refused the refund; nothing was written. */
    record Refused(RefundRefusal reason) implements RefundResult {}
}
