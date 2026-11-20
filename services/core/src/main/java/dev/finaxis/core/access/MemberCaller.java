package dev.finaxis.core.access;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * A member calling {@code /internal}, acting on a tenant of their organization.
 *
 * @param environment the tenant's, read when it was resolved, so issuing a key needs no second query
 */
public record MemberCaller(TenantId tenant, Environment environment, MemberId member, Set<Permission> permissions)
        implements Caller {

    public MemberCaller {
        permissions = Set.copyOf(permissions);
    }
}
