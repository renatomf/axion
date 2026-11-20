package dev.finaxis.core.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint that needs an authenticated caller but no permission, such as {@code /internal/me}.
 * Explicit, with its reason, so the exception is visible in review.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoPermissionRequired {

    String reason();
}
