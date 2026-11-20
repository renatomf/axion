package dev.finaxis.core.access.web;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.tenancy.Permission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API keys of a tenant, for the console's developer area (tenancy design, section 7; contract in
 * {@code openapi/internal.yaml}).
 *
 * <p>Before a method here runs, {@link PermissionInterceptor} has resolved the {@code {tenant_id}}
 * of the path through the member's own memberships and checked the method's permission; the
 * resolved {@link MemberCaller} arrives as a parameter. The tenant is read from the caller and from
 * nowhere else.
 */
@RestController
class ApiKeysController {

    private final AccessApi access;

    ApiKeysController(AccessApi access) {
        this.access = access;
    }

    /**
     * Lists every key of the tenant. No step-up: a list holds no secret and changes nothing, unlike
     * issuing, rolling or revoking a key.
     */
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @GetMapping("/internal/tenants/{tenant_id}/api_keys")
    ApiKeyListResponse list(MemberCaller caller) {
        return new ApiKeyListResponse(
                access.keys(caller.tenant()).stream().map(ApiKeyResponse::of).toList());
    }
}
