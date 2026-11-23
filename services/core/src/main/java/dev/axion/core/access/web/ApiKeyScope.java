package dev.axion.core.access.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A permission code an API key may carry, read from {@link dev.axion.core.tenancy.Permission}.
 * Placed on list elements, so a failure names the element, such as {@code scopes[1]}.
 */
@Documented
@Constraint(validatedBy = ApiKeyScopeValidator.class)
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.RUNTIME)
@interface ApiKeyScope {

    String message() default "must be an API key scope";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
