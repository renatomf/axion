package dev.finaxis.core.access.web;

import dev.finaxis.core.access.AccessApi;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

/**
 * Checks {@link ApiKeyName}. Hibernate Validator creates it by reflection, from its own package, so
 * the class is public; it is not a Spring bean.
 */
public final class ApiKeyNameValidator implements ConstraintValidator<ApiKeyName, String> {

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        return value == null || AccessApi.isValidName(value);
    }
}
