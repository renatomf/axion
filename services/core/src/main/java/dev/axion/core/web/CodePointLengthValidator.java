package dev.axion.core.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

// Public: Hibernate Validator instantiates it by reflection.
public final class CodePointLengthValidator implements ConstraintValidator<CodePointLength, String> {

    private int min;
    private int max;

    @Override
    public void initialize(CodePointLength constraint) {
        min = constraint.min();
        max = constraint.max();
    }

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.codePointCount(0, value.length());
        return length >= min && length <= max;
    }
}
