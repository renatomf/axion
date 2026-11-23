package dev.finaxis.core.risk;

/** The limits a tenant's charges are held to (billing design, section 6). */
public enum RiskRule {
    /** The amount of one charge. */
    AMOUNT,
    /** The amount of the charges created on one business date, in {@code America/Sao_Paulo}. */
    DAILY_AMOUNT,
    /** The number of charges created in one clock hour, in UTC. */
    HOURLY_COUNT
}
