package dev.finaxis.core.access;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * A member of an organization calling {@code /internal} with an access token, acting on one of the
 * organization's tenants (tenancy design, section 5.1).
 *
 * @param tenant the tenant named in the request path, resolved through the member's memberships
 * @param environment the tenant's environment, read when the tenant was resolved, so that code
 *     acting on the tenant, such as issuing an API key with the right prefix, needs no second query
 * @param member the person's member ID in the tenant's organization
 * @param permissions the permissions of the member's roles; the set cannot be changed
 */
public record MemberCaller(TenantId tenant, Environment environment, MemberId member, Set<Permission> permissions)
        implements Caller {

    public MemberCaller {
        permissions = Set.copyOf(permissions);
    }
}
