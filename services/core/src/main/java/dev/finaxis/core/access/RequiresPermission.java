package dev.finaxis.core.access;

import dev.finaxis.core.tenancy.Permission;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The one permission a tenant-scoped operation needs (tenancy design, section 5.2). It goes on the
 * controller method, which then receives the {@link Caller} as a parameter:
 *
 * <pre>{@code
 * @RequiresPermission(Permission.API_KEYS_MANAGE)
 * @PostMapping("/internal/tenants/{tenant_id}/api_keys")
 * ApiKeyResponse issue(Caller caller, @RequestBody IssueApiKeyRequest request) { … }
 * }</pre>
 *
 * <p>Before the method runs, the core resolves the caller's tenant and checks that the caller holds
 * the permission there. Every endpoint carries either this annotation or {@link
 * NoPermissionRequired}: one with neither is denied at runtime, and an architecture test fails the
 * build, so an operation cannot be open by omission.
 *
 * <p>{@code RUNTIME} retention keeps the annotation in the compiled class, where Spring reads it
 * while handling the request.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    Permission value();
}
