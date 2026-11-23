package dev.axion.core.webhooks.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A list of event types in which {@code *}, every type, appears only alone. */
@Documented
@Constraint(validatedBy = WildcardAloneValidator.class)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@interface WildcardAlone {

    String message() default "must be [\"*\"] alone or list event types";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
