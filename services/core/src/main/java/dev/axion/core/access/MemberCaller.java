package dev.axion.core.access;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.MemberId;
import dev.axion.core.tenancy.Permission;
import java.util.Set;

/** A member calling {@code /internal}, acting on a tenant of their organization. */
public record MemberCaller(TenantId tenant, Environment environment, MemberId member, Set<Permission> permissions)
        implements Caller {

    public MemberCaller {
        permissions = Set.copyOf(permissions);
    }
}
