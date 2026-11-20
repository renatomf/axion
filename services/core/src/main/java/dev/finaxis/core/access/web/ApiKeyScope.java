package dev.finaxis.core.access.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Bean Validation constraint: the text must be the code of a permission an API key may carry,
 * such as {@code charges:read}, as the {@code ApiKeyScope} schema of {@code openapi/internal.yaml}
 * lists them. An unknown code and a human-only permission, such as {@code api_keys:manage}, both
 * fail it. A {@code null} passes, as with every standard constraint; {@code @NotNull} rejects it.
 *
 * <p>The list of scopes comes from {@link dev.finaxis.core.tenancy.Permission}, so the code and the
 * check cannot drift apart. It goes on the elements of a list, as {@code List<@ApiKeyScope String>},
 * so a failure names the element, such as {@code scopes[1]}.
 *
 * <p>{@code @Constraint} tells the validator which class checks the annotation; {@code message},
 * {@code groups} and {@code payload} are the attributes Bean Validation requires of every
 * constraint.
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
