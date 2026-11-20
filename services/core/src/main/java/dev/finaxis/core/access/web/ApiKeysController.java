package dev.finaxis.core.access.web;

import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.IssueResult;
import dev.finaxis.core.access.MemberCaller;
import dev.finaxis.core.access.RequiresPermission;
import dev.finaxis.core.access.RequiresStepUp;
import dev.finaxis.core.access.RollResult;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.Valid;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ApiKeysController {

    private final AccessApi access;

    ApiKeysController(AccessApi access) {
        this.access = access;
    }

    // No step-up: a list holds no secret and changes nothing.
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @GetMapping("/internal/tenants/{tenant_id}/api_keys")
    ApiKeyListResponse list(MemberCaller caller) {
        return new ApiKeyListResponse(
                access.keys(caller.tenant()).stream().map(ApiKeyResponse::of).toList());
    }

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

    // Revoking twice returns the first revocation, so a retry is safe without an idempotency key.
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @RequiresStepUp
    @PostMapping("/internal/tenants/{tenant_id}/api_keys/{key_id}/revoke")
    ApiKeyResponse revoke(MemberCaller caller, @PathVariable("key_id") String keyId) {
        return access.revoke(caller, keyIn(keyId)).map(ApiKeyResponse::of).orElseThrow(AccessProblems::notFound);
    }

    // A key rolls once, so a retry answers 409 instead of issuing a second replacement.
    @RequiresPermission(Permission.API_KEYS_MANAGE)
    @RequiresStepUp
    @PostMapping("/internal/tenants/{tenant_id}/api_keys/{key_id}/roll")
    @ResponseStatus(HttpStatus.CREATED)
    RolledApiKeyResponse roll(
            MemberCaller caller,
            @PathVariable("key_id") String keyId,
            @Valid @RequestBody(required = false) @Nullable RollApiKeyRequest request) {
        Duration grace = request == null ? AccessApi.DEFAULT_ROLL_GRACE : request.gracePeriod();
        return switch (access.roll(caller, keyIn(keyId), grace)) {
            case RollResult.Rolled rolled -> RolledApiKeyResponse.of(rolled);
            case RollResult.KeyNotFound _ -> throw AccessProblems.notFound();
            case RollResult.KeyNotRollable _ -> throw AccessProblems.keyNotRollable();
            case RollResult.ScopesNotHeld _ -> throw AccessProblems.scopesNotHeld();
            case RollResult.LimitReached reached -> throw AccessProblems.limitExceeded(reached.limit());
        };
    }

    // A malformed ID answers 404, like another tenant's key, not 400.
    private static ApiKeyId keyIn(String text) {
        try {
            return new ApiKeyId(PublicId.parse(ApiKeyId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw AccessProblems.notFound();
        }
    }
}
