package dev.axion.core.billing.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A length in code points, as {@link dev.axion.core.billing.ChargeRequest}, PostgreSQL's {@code
 * char_length} and JSON Schema's {@code maxLength} count it. {@code Size} counts UTF-16 units, so
 * it would refuse 140 emoji that the charge accepts.
 */
@Documented
@Constraint(validatedBy = CodePointLengthValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
@interface CodePointLength {

    int min() default 0;

    int max();

    String message() default "must be {min} to {max} characters";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
