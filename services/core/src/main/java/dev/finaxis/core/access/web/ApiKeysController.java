package dev.finaxis.core.access.web;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.RequiresStepUp;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
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

    /**
     * Issues a key for the tenant and returns it with its secret, the only time the secret is shown.
     *
     * <p>The interceptor checks the permission and the step-up before Spring MVC reads the body, so a
     * member who may not issue keys learns nothing about what a valid body looks like. {@code @Valid}
     * then checks the body's constraints; a body that breaks one never reaches this method. The
     * outcomes the module returns as values become a key or a Problem Details response here.
     */
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @RequiresStepUp
    @PostMapping("/internal/tenants/{tenant_id}/api_keys")
    @ResponseStatus(HttpStatus.CREATED)
    IssuedApiKeyResponse issue(MemberCaller caller, @Valid @RequestBody IssueApiKeyRequest request) {
        return switch (access.issue(caller, request.name(), request.permissions())) {
            case IssueResult.Issued issued -> IssuedApiKeyResponse.of(issued.key());
            case IssueResult.ScopesNotHeld _ -> throw AccessProblems.scopesNotHeld();
            case IssueResult.LimitReached reached -> throw AccessProblems.limitExceeded(reached.limit());
        };
    }

    /**
     * Revokes one of the tenant's keys and returns it as lists show it. Revoking it again returns
     * the same key, with its first {@code revoked_at}, so the request has no body and needs no
     * idempotency key to be retried safely.
     *
     * <p>{@code @PathVariable} gives the method the {@code {key_id}} segment of the path as text. The
     * method parses it itself: a malformed ID, like a key of another tenant, answers {@code 404},
     * which a conversion failure in Spring MVC would answer as {@code 400}.
     */
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @RequiresStepUp
    @PostMapping("/internal/tenants/{tenant_id}/api_keys/{key_id}/revoke")
    ApiKeyResponse revoke(MemberCaller caller, @PathVariable("key_id") String keyId) {
        return access.revoke(caller, keyIn(keyId)).map(ApiKeyResponse::of).orElseThrow(AccessProblems::notFound);
    }

    /** The key a path names; a malformed ID names no key, so it answers {@code 404}. */
    private static ApiKeyId keyIn(String text) {
        try {
            return new ApiKeyId(PublicId.parse(ApiKeyId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw AccessProblems.notFound();
        }
    }
}
