package dev.axion.core.webhooks.web;

import dev.axion.core.webhooks.domain.EventTypes;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.List;
import org.jspecify.annotations.Nullable;

// Public: Hibernate Validator instantiates it by reflection.
public final class WildcardAloneValidator implements ConstraintValidator<WildcardAlone, List<?>> {

    @Override
    public boolean isValid(@Nullable List<?> value, ConstraintValidatorContext context) {
        return value == null || value.size() == 1 || !value.contains(EventTypes.ALL);
    }
}
