package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * Who calls a tenant-scoped operation, as authentication established it (tenancy design, section
 * 5.1). An operation reads its tenant from here and from nowhere else.
 */
public sealed interface Caller permits ApiKeyCaller, MemberCaller {

    TenantId tenant();

    Set<Permission> permissions();
}
