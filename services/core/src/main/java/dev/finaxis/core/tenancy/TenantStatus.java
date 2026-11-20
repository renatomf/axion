package dev.finaxis.core.tenancy;

/** Only an {@link #ACTIVE} tenant accepts requests (tenancy design, sections 3.4 and 4.4). */
public enum TenantStatus {
    ACTIVE,
    /** Blocked for now, for example while the platform investigates abuse. */
    SUSPENDED,
    /** Closed for good; its data is kept. */
    CLOSED
}
