package dev.finaxis.core.billing;

import dev.finaxis.core.risk.RiskRule;

/** The outcome of {@link BillingApi#createCharge}. */
public sealed interface CreateChargeResult {

    record Created(ChargeView charge) implements CreateChargeResult {}

    /** A risk rule of the tenant denied the charge (billing design, section 6); nothing was written. */
    record LimitExceeded(RiskRule rule) implements CreateChargeResult {}
}
