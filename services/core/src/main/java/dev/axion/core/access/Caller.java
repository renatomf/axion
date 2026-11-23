package dev.axion.core.access;

import dev.axion.core.shared.Actor;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.util.Set;

/**
 * Who calls a tenant-scoped operation, as authentication established it (tenancy design, section
 * 5.1). An operation reads its tenant from here and from nowhere else.
 */
public sealed interface Caller permits ApiKeyCaller, MemberCaller {

    TenantId tenant();

    /** The tenant's, read with its status, so code acting on the tenant needs no second query. */
    Environment environment();

    Set<Permission> permissions();

    /** The caller as the events of its changes name it. */
    Actor actor();
}
