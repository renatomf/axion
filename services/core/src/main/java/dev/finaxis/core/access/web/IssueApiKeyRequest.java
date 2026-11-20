package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * An {@code IssueApiKeyRequest} of {@code openapi/internal.yaml}: the body of a request to issue a
 * key. It has no tenant field on purpose: the tenant comes from the resolved caller, so a body
 * cannot name another one (tenancy design, section 5.1).
 *
 * <p>Jackson leaves a missing property {@code null}, but the controller takes the request as
 * {@code @Valid}, and {@code @NotNull} answers such a body with {@code 422} before the controller
 * runs. So by the time code reads a request, its components are non-null, and are declared so.
 *
 * @param name a label for the key
 * @param scopes what the key may do, as permission codes; a code listed twice counts once
 */
record IssueApiKeyRequest(
        @NotNull @ApiKeyName String name, @NotEmpty List<@NotNull @ApiKeyScope String> scopes) {

    /** The scopes as permissions; validation has already checked that every code is a scope. */
    Set<Permission> permissions() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : scopes) {
            permissions.add(Permission.fromCode(code));
        }
        return permissions;
    }
}
