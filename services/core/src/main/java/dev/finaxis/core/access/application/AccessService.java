package dev.finaxis.core.access.application;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RollResult;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.access.persistence.JdbcApiKeys;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.Permission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The access module's implementation of {@link AccessApi}. Other modules depend on the interface;
 * Spring passes them this bean, the only implementation.
 */
@Service
public class AccessService implements AccessApi {

    private final JdbcApiKeys keys;
    private final Clock clock;
    private final UuidV7Generator ids;
    // Thread-safe, so one instance serves every request.
    private final SecureRandom random = new SecureRandom();

    public AccessService(JdbcApiKeys keys, Clock clock) {
        this.keys = keys;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The limit is counted under a per-tenant lock held until the transaction ends, so concurrent
     * requests are counted one after the other and cannot pass the limit together. The arguments are
     * checked before the lock is taken, so a request that would fail anyway does not wait for it.
     */
    @Override
    @Transactional
    public IssueResult issue(MemberCaller issuer, String name, Set<Permission> scopes) {
        requireName(name);
        requireApiKeyScopes(scopes);
        Set<Permission> missing = EnumSet.copyOf(scopes);
        missing.removeAll(issuer.permissions());
        if (!missing.isEmpty()) {
            return new IssueResult.ScopesNotHeld(missing);
        }

        keys.lockTenantKeys(issuer.tenant());
        Instant now = now();
        if (keys.countActive(issuer.tenant(), now) >= MAX_ACTIVE_KEYS) {
            return new IssueResult.LimitReached(MAX_ACTIVE_KEYS);
        }

        return new IssueResult.Issued(insertKey(issuer, name, scopes, now));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> keys(TenantId tenant) {
        return keys.findByTenant(tenant);
    }

    @Override
    @Transactional
    public Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key) {
        keys.revoke(revoker.tenant(), key, now());
        return keys.find(revoker.tenant(), key);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Takes the same per-tenant lock as {@link #issue}, since the replacement counts towards the
     * limit. A revocation does not take it, so the old key is ended with a conditional update that
     * also catches a revocation made after the key was read.
     */
    @Override
    @Transactional
    public RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace) {
        requireGrace(grace);
        keys.lockTenantKeys(roller.tenant());
        Optional<ApiKeyView> found = keys.find(roller.tenant(), key);
        if (found.isEmpty()) {
            return new RollResult.KeyNotFound();
        }
        ApiKeyView old = found.get();
        if (old.revokedAt().isPresent() || old.expiresAt().isPresent()) {
            return new RollResult.KeyNotRollable();
        }
        // The replacement is a new key, so it may not do more than the member who rolls it could,
        // even if the member who issued the old key could.
        Set<Permission> missing = EnumSet.copyOf(old.scopes());
        missing.removeAll(roller.permissions());
        if (!missing.isEmpty()) {
            return new RollResult.ScopesNotHeld(missing);
        }

        Instant now = now();
        // The old key is working, so it is counted. During a grace period both keys work; without
        // one, the replacement takes the old key's place.
        int activeAfterRoll = keys.countActive(roller.tenant(), now) + (grace.isZero() ? 0 : 1);
        if (activeAfterRoll > MAX_ACTIVE_KEYS) {
            return new RollResult.LimitReached(MAX_ACTIVE_KEYS);
        }

        // Ends the old key first: if it no longer works, nothing has been written yet. Truncated
        // like now(), since a Duration may carry nanoseconds.
        if (!keys.expire(roller.tenant(), key, now.plus(grace).truncatedTo(ChronoUnit.MICROS))) {
            return new RollResult.KeyNotRollable();
        }
        IssuedApiKey replacement = insertKey(roller, old.name(), old.scopes(), now);
        return new RollResult.Rolled(
                replacement, keys.find(roller.tenant(), key).orElseThrow());
    }

    /** Stores a new key for the member's tenant and returns it with its secret. */
    private IssuedApiKey insertKey(MemberCaller issuer, String name, Set<Permission> scopes, Instant now) {
        // The tenant's environment, not the issuer's choice, decides the key's prefix.
        var secret = ApiKeySecret.generate(issuer.environment(), random);
        var id = new ApiKeyId(ids.next());
        keys.insert(id, issuer.tenant(), name, secret.hash(), secret.lastFour(), scopes, issuer.member(), now);
        return new IssuedApiKey(id, name, scopes, now, secret);
    }

    /**
     * The current instant, to the microsecond. PostgreSQL keeps microseconds; without truncating, an
     * instant returned to the caller would carry nanoseconds the stored row does not, and lists would
     * show another instant.
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** Counted by code point, as PostgreSQL's {@code char_length} counts, so an emoji counts once. */
    private static void requireName(String name) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("Key name must not be blank");
        }
        int length = name.codePointCount(0, name.length());
        if (length > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Key name must be at most " + MAX_NAME_LENGTH + " characters: " + length);
        }
    }

    private static void requireGrace(Duration grace) {
        if (grace.isNegative() || grace.compareTo(MAX_ROLL_GRACE) > 0) {
            throw new IllegalArgumentException(
                    "Grace period must be between zero and " + MAX_ROLL_GRACE + ": " + grace);
        }
    }

    /** The schema checks the same rules; checking here first gives a clear error, not a constraint name. */
    private static void requireApiKeyScopes(Set<Permission> scopes) {
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("A key needs at least one scope");
        }
        for (Permission scope : scopes) {
            if (!scope.isApiKeyScope()) {
                throw new IllegalArgumentException("Not an API key scope: " + scope.code());
            }
        }
    }
}
