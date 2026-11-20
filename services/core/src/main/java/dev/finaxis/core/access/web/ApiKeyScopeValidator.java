package dev.finaxis.core.access.web;

import dev.finaxis.core.tenancy.Permission;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

// Public: Hibernate Validator instantiates it by reflection.
public final class ApiKeyScopeValidator implements ConstraintValidator<ApiKeyScope, String> {

    private static final Set<String> SCOPES = Arrays.stream(Permission.values())
            .filter(Permission::isApiKeyScope)
            .map(Permission::code)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        return value == null || SCOPES.contains(value);
    }
}
