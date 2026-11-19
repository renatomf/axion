package dev.finaxis.core.access.web;

import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.tenancy.Membership;
import dev.finaxis.core.tenancy.TenantView;
import java.util.List;
import java.util.Locale;

/**
 * The body of {@code GET /internal/me}, shaped as the {@code CurrentMember} schema of {@code
 * openapi/internal.yaml}. Jackson writes a record as a JSON object with one property per component,
 * named as the component.
 *
 * <p>The response has its own types instead of returning {@link Membership}: the contract, not the
 * module's types, decides what the API shows, so a field added to {@code Membership} never leaks
 * into the response by accident.
 *
 * @param organizations the caller's organizations, in the order of {@code TenancyApi.memberships}
 */
record CurrentMemberResponse(List<Organization> organizations) {

    /** A {@code MemberOrganization} of the contract. */
    record Organization(String id, String name, List<String> roles, List<Tenant> tenants) {}

    /** A {@code Tenant} of the contract. */
    record Tenant(String id, String environment, String status) {}

    static CurrentMemberResponse of(List<Membership> memberships) {
        return new CurrentMemberResponse(
                memberships.stream().map(CurrentMemberResponse::organization).toList());
    }

    private static Organization organization(Membership membership) {
        return new Organization(
                new PublicId("org", membership.organization().value()).toString(),
                membership.organizationName(),
                // Sorted in the order Role declares them, so the response does not depend on how a
                // set happens to iterate.
                membership.roles().stream()
                        .sorted()
                        .map(CurrentMemberResponse::lowercase)
                        .toList(),
                membership.tenants().stream().map(CurrentMemberResponse::tenant).toList());
    }

    private static Tenant tenant(TenantView tenant) {
        return new Tenant(
                new PublicId("ten", tenant.id().value()).toString(),
                lowercase(tenant.environment()),
                lowercase(tenant.status()));
    }

    /** The API spells enumerations in lowercase snake_case: {@code READ_ONLY} becomes {@code read_only}. */
    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
