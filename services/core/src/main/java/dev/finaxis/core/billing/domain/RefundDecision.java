package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.RefundRefusal;

/** The outcome of {@link Charge#refund}. */
public sealed interface RefundDecision {

    /** The refund, and the charge with it. */
    record Refunded(Charge charge, Refund refund) implements RefundDecision {}

    /** Nothing changed. */
    record Refused(RefundRefusal reason) implements RefundDecision {}
}
