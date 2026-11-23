package dev.axion.core.access.web;

import dev.axion.core.access.AccessApi;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

// Public: Hibernate Validator instantiates it by reflection.
public final class ApiKeyNameValidator implements ConstraintValidator<ApiKeyName, String> {

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        return value == null || AccessApi.isValidName(value);
    }
}
