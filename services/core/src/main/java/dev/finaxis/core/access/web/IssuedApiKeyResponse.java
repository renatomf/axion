package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.List;

/**
 * An {@code IssuedApiKey} of {@code openapi/internal.yaml}: a key just issued, with its secret. This
 * response is the only place the secret ever leaves the core; afterwards, lists show the key as an
 * {@link ApiKeyResponse}, without it.
 *
 * <p>A record's generated {@code toString} would print every component, the secret included, so it
 * is overridden to mask it, as {@link IssuedApiKey} does.
 */
record IssuedApiKeyResponse(
        String id,
        String name,
        @JsonProperty("last_four") String lastFour,
        List<String> scopes,
        @JsonProperty("created_at") Instant createdAt,
        String secret) {

    static IssuedApiKeyResponse of(IssuedApiKey key) {
        return new IssuedApiKeyResponse(
                key.id().publicId().toString(),
                key.name(),
                key.lastFour(),
                // Alphabetical, as in a list, so the response does not depend on how a set iterates.
                key.scopes().stream().map(Permission::code).sorted().toList(),
                key.createdAt(),
                key.secret());
    }

    @Override
    public String toString() {
        return "IssuedApiKeyResponse[id=" + id + ", secret=…" + lastFour + "]";
    }
}
