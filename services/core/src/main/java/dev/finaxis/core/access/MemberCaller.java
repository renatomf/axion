package dev.finaxis.core.access;

import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/** A member calling {@code /internal}, acting on a tenant of their organization. */
public record MemberCaller(TenantId tenant, Environment environment, MemberId member, Set<Permission> permissions)
        implements Caller {

    public MemberCaller {
        permissions = Set.copyOf(permissions);
    }
}
