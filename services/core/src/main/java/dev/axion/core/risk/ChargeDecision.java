package dev.axion.core.risk;

/** The outcome of {@link RiskApi#assessCharge}. */
public sealed interface ChargeDecision {

    record Allowed() implements ChargeDecision {}

    /** The charge would break the rule; it was not counted. */
    record Denied(RiskRule rule) implements ChargeDecision {}
}
