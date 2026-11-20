package dev.finaxis.core.access.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Bean Validation constraint: the text must be a name the access module accepts for a key, by
 * its own rule ({@link dev.finaxis.core.access.AccessApi#isValidName}). The standard {@code
 * NotBlank} and {@code Size} would disagree with it at the edges: {@code NotBlank} accepts a name of
 * Unicode spaces that {@link String#isBlank} rejects, and {@code Size} counts an emoji as two
 * characters. A {@code null} passes, as with every standard constraint; {@code @NotNull} rejects it.
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
