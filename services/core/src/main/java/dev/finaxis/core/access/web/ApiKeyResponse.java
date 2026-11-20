package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * An {@code ApiKey} of {@code openapi/internal.yaml}: a key as lists show it, never with its secret.
 *
 * <p>The contract spells properties in snake_case; {@code @JsonProperty} gives Jackson the JSON name
 * of each component whose Java name differs. Dates are written as RFC 3339 text, such as {@code
 * 2026-11-19T12:00:00Z}, Jackson's default for an {@link Instant}. A date that has not happened is
 * written as {@code null}, not left out, as the contract requires.
 */
record ApiKeyResponse(
        String id,
        String name,
        @JsonProperty("last_four") String lastFour,
        List<String> scopes,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("expires_at") @Nullable Instant expiresAt,
        @JsonProperty("revoked_at") @Nullable Instant revokedAt,
        @JsonProperty("last_used_at") @Nullable Instant lastUsedAt) {

    static ApiKeyResponse of(ApiKeyView key) {
        return new ApiKeyResponse(
                key.id().publicId().toString(),
                key.name(),
                key.lastFour(),
                // Alphabetical, so the response does not depend on how a set happens to iterate.
                key.scopes().stream().map(Permission::code).sorted().toList(),
                key.createdAt(),
                key.expiresAt().orElse(null),
                key.revokedAt().orElse(null),
                key.lastUsedAt().orElse(null));
    }
}
