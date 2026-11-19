package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import java.util.Optional;

/**
 * The only way other modules use the tenancy directory (tenancy design, section 7). Calls run in
 * the caller's transaction when there is one.
 *
 * <p>Membership lookups join this interface with their first callers, in the pull requests that
 * authenticate members.
 */
public interface TenancyApi {

    /**
     * Creates an organization with its two tenants, both active, and its first member, an admin.
     * Everything is written in one transaction, so an organization never exists without both
     * tenants or without an admin (section 3.3).
     *
     * @param name the display name, 1 to {@value #MAX_NAME_LENGTH} characters, not blank
     * @param adminSubject the Keycloak {@code sub} of the person who becomes the admin, 1 to
     *     {@value #MAX_SUBJECT_LENGTH} characters, not blank
     * @throws IllegalArgumentException if the name or the subject is blank or too long
     */
    NewOrganization createOrganization(String name, String adminSubject);

    /** The tenant with this ID, if there is one. */
    Optional<TenantView> tenant(TenantId tenant);

    /**
     * The platform's own tenant in this environment, which owns accounts such as {@code
     * platform:settlement}. Each environment has its own, so test money never reaches live accounts.
     */
    TenantId platformTenant(Environment environment);

    /** The longest organization name, in characters, as the schema allows. */
    int MAX_NAME_LENGTH = 100;

    /** The longest Keycloak subject, in characters, as the schema allows. */
    int MAX_SUBJECT_LENGTH = 255;
}
