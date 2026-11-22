package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;

/**
 * A tenant as other modules see it.
 *
 * @param organizationName the name of the tenant's organization, such as billing writes in a BR Code
 */
public record TenantView(
        TenantId id,
        OrganizationId organization,
        String organizationName,
        Environment environment,
        TenantStatus status) {}
