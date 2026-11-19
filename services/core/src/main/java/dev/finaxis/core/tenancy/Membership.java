package dev.finaxis.core.tenancy;

import java.util.List;
import java.util.Set;

/**
 * A person's place in one organization: who they are there, their roles, and the organization's
 * tenants (tenancy design, section 3.1). Roles apply to both tenants alike.
 *
 * @param member the person's member ID in this organization
 * @param organization the organization
 * @param organizationName the organization's display name
 * @param roles the member's roles; the set cannot be changed
 * @param tenants the organization's tenants, test first, then live; the list cannot be changed
 */
public record Membership(
        MemberId member,
        OrganizationId organization,
        String organizationName,
        Set<Role> roles,
        List<TenantView> tenants) {

    public Membership {
        roles = Set.copyOf(roles);
        tenants = List.copyOf(tenants);
    }
}
