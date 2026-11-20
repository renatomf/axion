package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/** A tenant's system calling {@code /v1}: the key's own tenant and scopes, never the request's. */
public record ApiKeyCaller(TenantId tenant, ApiKeyId key, Set<Permission> permissions) implements Caller {

    public ApiKeyCaller {
        permissions = Set.copyOf(permissions);
    }
}
