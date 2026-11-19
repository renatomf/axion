package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * An API key as lists show it (tenancy design, section 7): what it is called, what it may do and
 * what has happened to it, but never its secret, which no one can read after it is issued.
 *
 * @param id the key
 * @param name the label the issuer chose
 * @param lastFour the key's last four characters, so a member can tell keys apart
 * @param scopes what the key may do; the set cannot be changed
 * @param createdAt when it was issued
 * @param expiresAt the end of a roll's grace period, if the key was rolled
 * @param revokedAt when it was revoked, if it was
 * @param lastUsedAt when it last authenticated a request, to the hour, if it ever did
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
