package dev.finaxis.core.access.application;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.access.persistence.JdbcApiKeys;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.Permission;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
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
        // PostgreSQL keeps microseconds; without truncating, the key returned here would carry
        // nanoseconds the stored key does not, and lists would show it issued at another instant.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (keys.countActive(issuer.tenant(), now) >= MAX_ACTIVE_KEYS) {
            return new IssueResult.LimitReached(MAX_ACTIVE_KEYS);
        }

        // The tenant's environment, not the issuer's choice, decides the key's prefix.
        var secret = ApiKeySecret.generate(issuer.environment(), random);
        var id = new ApiKeyId(ids.next());
        keys.insert(id, issuer.tenant(), name, secret.hash(), secret.lastFour(), scopes, issuer.member(), now);
        return new IssueResult.Issued(new IssuedApiKey(id, name, scopes, now, secret));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ApiKeyView> keys(TenantId tenant) {
        return keys.findByTenant(tenant);
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
