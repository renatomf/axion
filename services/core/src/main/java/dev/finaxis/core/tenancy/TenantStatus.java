package dev.finaxis.core.tenancy;

/**
 * Whether a tenant may be used (tenancy design, section 3.4). Only an {@link #ACTIVE} tenant
 * accepts requests; a valid credential of any other tenant gets {@code 403 tenant_inactive}
 * (section 4.4).
 *
 * <p>The names match the {@code status} column of {@code tenancy.tenants}.
 */
public enum TenantStatus {
    ACTIVE,
    /** Blocked for now, for example while the platform investigates abuse. */
    SUSPENDED,
    /** Closed for good; its data is kept. */
    CLOSED
}
