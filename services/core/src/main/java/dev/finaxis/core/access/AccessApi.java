package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.util.List;
import java.util.Set;

/**
 * The API keys of a tenant (tenancy design, sections 4.3 and 7). Calls run in the caller's
 * transaction when there is one.
 *
 * <p>Rolling and revoking keys join this interface in the next commits and pull requests.
 */
public interface AccessApi {

    /**
     * Issues a key for the issuer's tenant, with a name and scopes.
     *
     * <p>The issuer is a member already resolved to the tenant, so the tenant exists and the member
     * belongs to it. Checking that the member may manage API keys, and has recently authenticated, is
     * the endpoint's job ({@code @RequiresPermission} and {@code @RequiresStepUp}).
     *
     * @param name a label for the key, 1 to {@value #MAX_NAME_LENGTH} characters, not blank
     * @param scopes what the key may do: at least one permission, every one of them an API key scope
     * @return {@link IssueResult.Issued} with the key and its secret, {@link
     *     IssueResult.ScopesNotHeld} if the issuer lacks one of the scopes, or {@link
     *     IssueResult.LimitReached} if the tenant already has {@value #MAX_ACTIVE_KEYS} working keys
     * @throws IllegalArgumentException if the name is blank or too long, there are no scopes, or one
     *     of them is a human-only permission, which the endpoint's contract rejects first
     */
    IssueResult issue(MemberCaller issuer, String name, Set<Permission> scopes);

    /**
     * Every key of the tenant, newest first, including revoked and expired ones: a key is never
     * deleted, and its row is the record of what it could do. A tenant has at most {@value
     * #MAX_ACTIVE_KEYS} working keys, but the keys it revoked keep accumulating.
     */
    List<ApiKeyView> keys(TenantId tenant);

    /** The longest key name, in characters, as the schema allows. */
    int MAX_NAME_LENGTH = 100;

    /** The most keys a tenant may have that are neither revoked nor expired (section 4.3). */
    int MAX_ACTIVE_KEYS = 10;
}
