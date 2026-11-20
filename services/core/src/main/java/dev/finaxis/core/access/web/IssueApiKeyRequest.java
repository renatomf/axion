package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

// No tenant field: the tenant comes from the resolved caller, so a body cannot name another.
record IssueApiKeyRequest(
        @NotNull @ApiKeyName String name, @NotEmpty List<@NotNull @ApiKeyScope String> scopes) {

    Set<Permission> permissions() {
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        for (String code : scopes) {
            permissions.add(Permission.fromCode(code));
        }
        return permissions;
    }
}
