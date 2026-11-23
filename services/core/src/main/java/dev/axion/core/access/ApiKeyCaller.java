package dev.axion.core.access;

import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.util.Set;

/** A tenant's system calling {@code /v1}: the key's own tenant and scopes, never the request's. */
public record ApiKeyCaller(TenantId tenant, Environment environment, ApiKeyId key, Set<Permission> permissions)
        implements Caller {

    public ApiKeyCaller {
        permissions = Set.copyOf(permissions);
    }
}
