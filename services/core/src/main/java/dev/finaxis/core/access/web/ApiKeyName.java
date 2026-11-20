package dev.finaxis.core.access.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The module's own name rule, {@link dev.finaxis.core.access.AccessApi#isValidName}. {@code
 * NotBlank} and {@code Size} disagree with it on Unicode spaces and on emoji.
 */
@Documented
@Constraint(validatedBy = ApiKeyNameValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface ApiKeyName {

    String message() default "must be 1 to 100 characters, not all blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
