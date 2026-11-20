package dev.finaxis.core.access.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyCaller;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.access.AuthenticationResult;
import dev.finaxis.core.access.AuthenticationResult.Reason;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RollResult;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.access.persistence.JdbcApiKeys;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantStatus;
import dev.finaxis.core.tenancy.TenantView;
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

@Service
public class AccessService implements AccessApi {

    private static final String ROLE = "finaxis_access";

    private final JdbcApiKeys keys;
    private final TenancyApi tenancy;
    private final ModuleRoles roles;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final SecureRandom random = new SecureRandom();

    public AccessService(JdbcApiKeys keys, TenancyApi tenancy, ModuleRoles roles, Clock clock) {
        this.keys = keys;
        this.tenancy = tenancy;
        this.roles = roles;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

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
        return roles.call(ROLE, () -> issueWithinLimit(issuer, name, scopes));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> keys(TenantId tenant) {
        return roles.call(ROLE, () -> keys.findByTenant(tenant));
    }

    @Override
    @Transactional
    public Optional<ApiKeyView> revoke(MemberCaller revoker, ApiKeyId key) {
        return roles.call(ROLE, () -> {
            keys.revoke(revoker.tenant(), key, now());
            return keys.find(revoker.tenant(), key);
        });
    }

    @Override
    @Transactional
    public RollResult roll(MemberCaller roller, ApiKeyId key, Duration grace) {
        requireGrace(grace);
        return roles.call(ROLE, () -> rollKey(roller, key, grace));
    }

    @Override
    @Transactional
    public AuthenticationResult authenticate(String credential) {
        Optional<ApiKeySecret> secret = ApiKeySecret.parse(credential);
        if (secret.isEmpty()) {
            return new AuthenticationResult.Rejected(Reason.MALFORMED, Optional.empty());
        }
        return roles.call(ROLE, () -> authenticateKey(secret.get()));
    }

    private IssueResult issueWithinLimit(MemberCaller issuer, String name, Set<Permission> scopes) {
        // Concurrent issues are counted one after another, so they cannot pass the limit together.
        keys.lockTenantKeys(issuer.tenant());
        Instant now = now();
        if (keys.countActive(issuer.tenant(), now) >= MAX_ACTIVE_KEYS) {
            return new IssueResult.LimitReached(MAX_ACTIVE_KEYS);
        }

        return new IssueResult.Issued(insertKey(issuer, name, scopes, now));
    }

    private RollResult rollKey(MemberCaller roller, ApiKeyId key, Duration grace) {
        keys.lockTenantKeys(roller.tenant());
        Optional<ApiKeyView> found = keys.find(roller.tenant(), key);
        if (found.isEmpty()) {
            return new RollResult.KeyNotFound();
        }
        ApiKeyView old = found.get();
        if (old.revokedAt().isPresent() || old.expiresAt().isPresent()) {
            return new RollResult.KeyNotRollable();
        }
        // The replacement may not exceed the roller's permissions, whoever issued the old key.
        Set<Permission> missing = EnumSet.copyOf(old.scopes());
        missing.removeAll(roller.permissions());
        if (!missing.isEmpty()) {
            return new RollResult.ScopesNotHeld(missing);
        }

        Instant now = now();
        // During a grace period both keys work; without one, the replacement takes the old key's place.
        int activeAfterRoll = keys.countActive(roller.tenant(), now) + (grace.isZero() ? 0 : 1);
        if (activeAfterRoll > MAX_ACTIVE_KEYS) {
            return new RollResult.LimitReached(MAX_ACTIVE_KEYS);
        }

        // Conditional: a revocation does not take the lock, so it may have happened since the read.
        if (!keys.expire(roller.tenant(), key, now.plus(grace).truncatedTo(ChronoUnit.MICROS))) {
            return new RollResult.KeyNotRollable();
        }
        IssuedApiKey replacement = insertKey(roller, old.name(), old.scopes(), now);
        return new RollResult.Rolled(
                replacement, keys.find(roller.tenant(), key).orElseThrow());
    }

    private AuthenticationResult authenticateKey(ApiKeySecret secret) {
        Optional<JdbcApiKeys.Credential> found = keys.findByHash(secret.hash());
        if (found.isEmpty()) {
            return new AuthenticationResult.Rejected(Reason.UNKNOWN, Optional.empty());
        }
        JdbcApiKeys.Credential key = found.get();
        if (key.revokedAt().isPresent()) {
            return new AuthenticationResult.Rejected(Reason.REVOKED, Optional.of(key.id()));
        }
        Instant now = now();
        if (key.expiresAt().filter(end -> !end.isAfter(now)).isPresent()) {
            return new AuthenticationResult.Rejected(Reason.EXPIRED, Optional.of(key.id()));
        }
        // Checked after the key, so an invalid credential never learns whether a tenant is active.
        TenantView tenant = tenancy.tenant(key.tenant()).orElseThrow();
        if (tenant.status() != TenantStatus.ACTIVE) {
            return new AuthenticationResult.TenantInactive();
        }
        if (key.useIsDue(now)) {
            keys.recordUse(secret.hash(), now);
        }
        return new AuthenticationResult.Authenticated(new ApiKeyCaller(key.tenant(), key.id(), key.scopes()));
    }

    private IssuedApiKey insertKey(MemberCaller issuer, String name, Set<Permission> scopes, Instant now) {
        // The tenant's environment, not the issuer's choice, decides the key's prefix.
        var secret = ApiKeySecret.generate(issuer.environment(), random);
        var id = new ApiKeyId(ids.next());
        keys.insert(id, issuer.tenant(), name, secret.hash(), secret.lastFour(), scopes, issuer.member(), now);
        return new IssuedApiKey(id, name, scopes, now, secret);
    }

    // PostgreSQL keeps microseconds; a returned instant must match the stored row.
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void requireName(String name) {
        if (!AccessApi.isValidName(name)) {
            throw new IllegalArgumentException(
                    name.isBlank()
                            ? "Key name must not be blank"
                            : "Key name must be at most " + MAX_NAME_LENGTH + " characters");
        }
    }

    private static void requireGrace(Duration grace) {
        if (grace.isNegative() || grace.compareTo(MAX_ROLL_GRACE) > 0) {
            throw new IllegalArgumentException(
                    "Grace period must be between zero and " + MAX_ROLL_GRACE + ": " + grace);
        }
    }

    // The schema checks the same rules; checking first gives a clear error, not a constraint name.
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
