package dev.finaxis.core.access;

import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.Permission;
import java.util.Set;

/**
 * A tenant's system calling {@code /v1} with an API key (tenancy design, section 5.1). The tenant is
 * the key's own and the permissions are its scopes: nothing in the request can change either.
 *
 * @param tenant the tenant the key belongs to, read from the stored key, never from its prefix
 * @param key the key that authenticated the request, for logs and the key's {@code last_used_at}
 * @param permissions the key's scopes; the set cannot be changed
 */
public record ApiKeyCaller(TenantId tenant, ApiKeyId key, Set<Permission> permissions) implements Caller {

    public ApiKeyCaller {
        permissions = Set.copyOf(permissions);
    }
}
