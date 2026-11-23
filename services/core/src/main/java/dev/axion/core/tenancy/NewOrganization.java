package dev.axion.core.tenancy;

import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;

/** What {@link TenancyApi#createOrganization} created; {@code admin} is its first member. */
public record NewOrganization(OrganizationId organization, TenantId test, TenantId live, MemberId admin) {}
