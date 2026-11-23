package dev.axion.core.tenancy;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * The only way other modules use the tenancy directory (tenancy design, section 7). Calls run in
 * the caller's transaction when there is one.
 */
public interface TenancyApi {

    /**
     * Creates an organization with its two active tenants and its first member, an admin, in one
     * transaction (section 3.3).
     *
     * @param adminSubject the Keycloak {@code sub} of the first admin
     * @throws IllegalArgumentException if the name or the subject is blank or too long
     */
    NewOrganization createOrganization(String name, String adminSubject);

    Optional<TenantView> tenant(TenantId tenant);

    /** The person's memberships, by organization name; {@code userSubject} is the Keycloak {@code sub}. */
    List<Membership> memberships(String userSubject);

    /**
     * The person's membership in the tenant's organization, whatever the tenant's status. A missing
     * tenant and a foreign one are both empty, so a caller cannot learn which tenants exist.
     */
    Optional<Membership> membership(String userSubject, TenantId tenant);

    /** The platform's own tenant in this environment, so test money never reaches live accounts. */
    TenantId platformTenant(Environment environment);

    int MAX_NAME_LENGTH = 100;

    int MAX_SUBJECT_LENGTH = 255;
}
