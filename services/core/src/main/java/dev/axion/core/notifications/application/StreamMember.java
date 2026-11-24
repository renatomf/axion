package dev.axion.core.notifications.application;

import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.util.Set;

/** The member a stream is open for, on the tenant of its path, with the permissions they hold there. */
public record StreamMember(TenantId tenant, MemberId member, Set<Permission> permissions) {

    public StreamMember {
        permissions = Set.copyOf(permissions);
    }
}
