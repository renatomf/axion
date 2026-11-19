package dev.finaxis.core.shared;

/**
 * The environment of a tenant (tenancy design, section 3.2). Every organization has one tenant per
 * environment, so test and live data, and test and live money, never mix.
 *
 * <p>The names match the {@code environment} column of {@code tenancy.tenants}.
 */
public enum Environment {
    /** Simulated money, for integration work. */
    TEST,
    /** The "real" environment of the demonstration. */
    LIVE
}
