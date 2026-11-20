package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

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
                // Sorted, so the response does not depend on set iteration order.
                key.scopes().stream().map(Permission::code).sorted().toList(),
                key.createdAt(),
                key.expiresAt().orElse(null),
                key.revokedAt().orElse(null),
                key.lastUsedAt().orElse(null));
    }
}
