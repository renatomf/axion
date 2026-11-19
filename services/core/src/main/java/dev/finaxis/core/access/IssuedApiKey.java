package dev.finaxis.core.access;

import dev.finaxis.core.access.domain.ApiKeySecret;
import dev.finaxis.core.tenancy.Permission;
import java.time.Instant;
import java.util.Set;

/**
 * A key just issued, with its secret: the only type that holds one, returned once by {@link
 * AccessApi#issue} and never again (tenancy design, section 7).
 *
 * <p>A class rather than a record, because a record's generated {@code toString} would print every
 * component. This one masks the secret, so a key logged or shown in an exception by mistake does
 * not leak; only {@link #secret} returns it.
 */
public final class IssuedApiKey {

    private final ApiKeyId id;
    private final String name;
    private final Set<Permission> scopes;
    private final Instant createdAt;
    private final ApiKeySecret secret;

    /** Meant for the access module; the secret's type is internal to it. */
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

    /** The label the issuer chose. */
    public String name() {
        return name;
    }

    /** What the key may do; the set cannot be changed. */
    public Set<Permission> scopes() {
        return scopes;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** The whole key, to be shown once to the member who issued it. */
    public String secret() {
        return secret.reveal();
    }

    /** The key's last four characters, which lists show from now on. */
    public String lastFour() {
        return secret.lastFour();
    }

    @Override
    public String toString() {
        return "IssuedApiKey[id=" + id.publicId() + ", name=" + name + ", secret=" + secret + "]";
    }
}
