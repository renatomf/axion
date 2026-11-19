package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.TenantId;

/**
 * What {@link TenancyApi#createOrganization} created.
 *
 * @param organization the new organization
 * @param test its test tenant
 * @param live its live tenant
 * @param admin its first member, with the admin role
 */
public record NewOrganization(OrganizationId organization, TenantId test, TenantId live, MemberId admin) {}
