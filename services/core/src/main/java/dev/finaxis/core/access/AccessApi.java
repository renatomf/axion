package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The API keys of a tenant (tenancy design, sections 4.3 and 7). Calls run in the caller's
 * transaction when there is one.
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

    /**
     * Revokes one of the tenant's keys: it stops working on the next request, and for good. Revoking
     * a key that is already revoked changes nothing, so a retried request is harmless.
     *
     * <p>Like {@link #issue}, it takes the resolved member, not a bare key ID: the key is looked up
     * only among the member's tenant's keys, so a key of another tenant is treated as one that does
     * not exist. Checking the permission and the step-up is the endpoint's job.
     *
     * @return the key as it is now, or empty if the tenant has no key with this ID
     */
    Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key);

    /**
     * Replaces one of the tenant's keys without downtime: issues a new key with the same name and
     * scopes, and lets the old one keep working for a grace period, so the tenant can deploy the new
     * key before the old one stops. A grace period of zero ends the old key at once.
     *
     * <p>Like {@link #revoke}, it takes the resolved member, so a key of another tenant is treated as
     * one that does not exist, and the new key records this member as its issuer. Checking the
     * permission and the step-up is the endpoint's job.
     *
     * @param grace how long the old key keeps working, from zero to {@link #MAX_ROLL_GRACE}
     * @return {@link RollResult.Rolled} with the new key and its secret, {@link
     *     RollResult.KeyNotFound} if the tenant has no key with this ID, {@link
     *     RollResult.KeyNotRollable} if the key was revoked or already rolled, {@link
     *     RollResult.ScopesNotHeld} if the member lacks one of the key's scopes, or {@link
     *     RollResult.LimitReached} if the replacement would pass {@value #MAX_ACTIVE_KEYS} working
     *     keys
     * @throws IllegalArgumentException if the grace period is negative or longer than {@link
     *     #MAX_ROLL_GRACE}, which the endpoint's contract rejects first
     */
    RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace);

    /**
     * Resolves the credential a caller sent on {@code /v1} to the key it names (section 4.4).
     *
     * <p>A text that is not a key, in shape or checksum, is rejected before any query; otherwise one
     * indexed lookup by the secret's hash finds the key. The key's tenant and scopes come from the
     * stored key, never from the request. A key that authenticates has its last use recorded, to
     * within an hour.
     *
     * @param credential the bearer token exactly as the caller sent it, which may be any text
     * @return {@link AuthenticationResult.Authenticated} with the caller for a working key of an
     *     active tenant, {@link AuthenticationResult.TenantInactive} for a working key of any other
     *     tenant, or {@link AuthenticationResult.Rejected} with the reason
     */
    AuthenticationResult authenticate(String credential);

    /** The longest key name, in characters, as the schema allows. */
    int MAX_NAME_LENGTH = 100;

    /**
     * Whether {@link #issue} accepts a key name: not blank, as {@link String#isBlank} decides, and at
     * most {@value #MAX_NAME_LENGTH} characters, counted by code point as PostgreSQL's {@code
     * char_length} counts, so an emoji counts once. Endpoints check a request with this rule, so that
     * their validation and the module's never disagree.
     */
    static boolean isValidName(String name) {
        return !name.isBlank() && name.codePointCount(0, name.length()) <= MAX_NAME_LENGTH;
    }

    /** The most keys a tenant may have that are neither revoked nor expired (section 4.3). */
    int MAX_ACTIVE_KEYS = 10;

    /** The longest grace period a roll may give the old key (section 4.3). */
    Duration MAX_ROLL_GRACE = Duration.ofDays(7);

    /** The grace period the endpoint gives when the request names none (section 4.3). */
    Duration DEFAULT_ROLL_GRACE = Duration.ofHours(24);
}
