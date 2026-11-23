package dev.axion.core.shared;

/** One tenant per environment, so test and live money never mix (tenancy design, section 3.2). */
public enum Environment {
    TEST,
    LIVE
}
