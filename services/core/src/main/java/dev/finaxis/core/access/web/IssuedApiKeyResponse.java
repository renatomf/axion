package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.List;

// The only place a key's secret leaves the core.
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
                // Sorted, as in a list.
                key.scopes().stream().map(Permission::code).sorted().toList(),
                key.createdAt(),
                key.secret());
    }

    // The generated toString would print the secret.
    @Override
    public String toString() {
        return "IssuedApiKeyResponse[id=" + id + ", secret=…" + lastFour + "]";
    }
}
