package dev.finaxis.core.billing;

/** The outcome of {@link BillingApi#createCharge}. */
public sealed interface CreateChargeResult {

    record Created(ChargeView charge) implements CreateChargeResult {}
}
