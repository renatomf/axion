package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * Who is calling a tenant-scoped operation, as authentication established it (tenancy design,
 * section 5.1): the tenant the call acts on and the permissions it holds there. An operation reads
 * its tenant from here and from nowhere else.
 *
 * <p>The interface is {@code sealed}: the compiler knows every kind of caller, so a {@code switch}
 * over a {@code Caller} must handle them all.
 */
public sealed interface Caller permits ApiKeyCaller, MemberCaller {

    /** The tenant the call acts on. */
    TenantId tenant();

    /** The permissions the caller holds on that tenant; the set cannot be changed. */
    Set<Permission> permissions();
}
