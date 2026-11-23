package dev.axion.core.access;

import dev.axion.core.tenancy.Permission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * An API key as lists show it, never with its secret.
 *
 * @param expiresAt the end of a roll's grace period, if the key was rolled
 * @param lastUsedAt when it last authenticated a request, to within an hour
 */
public record ApiKeyView(
        ApiKeyId id,
        String name,
        String lastFour,
        Set<Permission> scopes,
        Instant createdAt,
        Optional<Instant> expiresAt,
        Optional<Instant> revokedAt,
        Optional<Instant> lastUsedAt) {

    public ApiKeyView {
        scopes = Set.copyOf(scopes);
    }
}
