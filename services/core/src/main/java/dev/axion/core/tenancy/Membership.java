package dev.axion.core.tenancy;

import dev.axion.core.shared.MemberId;
import java.util.List;
import java.util.Set;

/**
 * A person's place in one organization (tenancy design, section 3.1). Roles apply to both tenants.
 *
 * @param tenants test first, then live
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
