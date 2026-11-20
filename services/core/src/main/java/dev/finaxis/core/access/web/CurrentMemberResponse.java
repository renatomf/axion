package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.Membership;
import dev.finaxis.core.tenancy.TenantView;
import java.util.List;
import java.util.Locale;

// Own types, not Membership: a field added to the module's type never leaks into the response.
record CurrentMemberResponse(List<Organization> organizations) {

    record Organization(String id, String name, List<String> roles, List<Tenant> tenants) {}

    record Tenant(String id, String environment, String status) {}

    static CurrentMemberResponse of(List<Membership> memberships) {
        return new CurrentMemberResponse(
                memberships.stream().map(CurrentMemberResponse::organization).toList());
    }

    private static Organization organization(Membership membership) {
        return new Organization(
                membership.organization().publicId().toString(),
                membership.organizationName(),
                // Sorted, so the response does not depend on set iteration order.
                membership.roles().stream()
                        .sorted()
                        .map(CurrentMemberResponse::lowercase)
                        .toList(),
                membership.tenants().stream().map(CurrentMemberResponse::tenant).toList());
    }

    private static Tenant tenant(TenantView tenant) {
        return new Tenant(
                tenant.id().publicId().toString(), lowercase(tenant.environment()), lowercase(tenant.status()));
    }

    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
