package dev.axion.core.access;

import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The API keys of a tenant (tenancy design, sections 4.3 and 7). Calls run in the caller's
 * transaction when there is one. Issuing, revoking and rolling publish their events in it, with the
 * tenant and actor of the current work bound.
 */
public interface AccessApi {

    /**
     * Issues a key for the issuer's tenant, publishing {@link ApiKeyIssued}. Scopes must be
     * permissions the issuer holds.
     *
     * @throws IllegalArgumentException for an invalid name or a scope that is not an API key scope
     */
    IssueResult issue(MemberCaller issuer, String name, Set<Permission> scopes);

    /** Every key of the tenant, newest first, revoked and expired ones included: keys are never deleted. */
    List<ApiKeyView> keys(TenantId tenant);

    /**
     * Revokes one of the revoker's tenant's keys, publishing {@link ApiKeyRevoked}; revoking it again
     * changes nothing and publishes nothing.
     *
     * @return the key as it is now, or empty if the tenant has no such key
     */
    Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key);

    /**
     * Replaces a key without downtime: a new key with the same name and scopes, while the old one
     * keeps working for the grace period. One {@link ApiKeyRolled} records both.
     *
     * @throws IllegalArgumentException if the grace period is outside zero to {@link #MAX_ROLL_GRACE}
     */
    RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace);

    /**
     * Resolves a {@code /v1} credential, any text, to the key it names (section 4.4). Tenant and
     * scopes come from the stored key, never from the request.
     */
    AuthenticationResult authenticate(String credential);

    int MAX_NAME_LENGTH = 100;

    /** Not blank, and at most {@value #MAX_NAME_LENGTH} code points, as PostgreSQL's {@code char_length} counts. */
    static boolean isValidName(String name) {
        return !name.isBlank() && name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH;
    }

    /** Working keys: neither revoked nor expired. */
    int MAX_ACTIVE_KEYS = 10;

    Duration MAX_ROLL_GRACE = Duration.ofDays(7);

    Duration DEFAULT_ROLL_GRACE = Duration.ofHours(24);
}
