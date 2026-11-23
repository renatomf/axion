package dev.axion.core.access;

import dev.axion.core.access.domain.ApiKeySecret;
import dev.axion.core.tenancy.Permission;
import java.time.Instant;
import java.util.Set;

/**
 * A key just issued, with its secret, returned once and never again. A class, not a record, so
 * {@code toString} masks the secret.
 */
public final class IssuedApiKey {

    private final ApiKeyId id;
    private final String name;
    private final Set<Permission> scopes;
    private final Instant createdAt;
    private final ApiKeySecret secret;

    public IssuedApiKey(ApiKeyId id, String name, Set<Permission> scopes, Instant createdAt, ApiKeySecret secret) {
        this.id = id;
        this.name = name;
        this.scopes = Set.copyOf(scopes);
        this.createdAt = createdAt;
        this.secret = secret;
    }

    public ApiKeyId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Set<Permission> scopes() {
        return scopes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public String secret() {
        return secret.reveal();
    }

    public String lastFour() {
        return secret.lastFour();
    }

    @Override
    public String toString() {
        return "IssuedApiKey[id=" + id.publicId() + ", name=" + name + ", secret=" + secret + "]";
    }
}
