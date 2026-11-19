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

    /** The tenant with this ID, if there is one. */
    Optional<TenantView> tenant(TenantId tenant);

    /**
     * The platform's own tenant in this environment, which owns accounts such as {@code
     * platform:settlement}. Each environment has its own, so test money never reaches live accounts.
     */
    TenantId platformTenant(Environment environment);
}
