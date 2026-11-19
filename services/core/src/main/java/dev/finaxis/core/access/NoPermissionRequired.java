package dev.finaxis.core.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an endpoint that needs an authenticated caller but no permission, such as {@code
 * /internal/me}, which shows members only their own memberships. It acts on no tenant, so it
 * receives no {@link Caller}.
 *
 * <p>The exception is explicit, with its reason, so a reviewer sees it and the architecture test
 * that requires {@link RequiresPermission} on every endpoint accepts it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NoPermissionRequired {

    /** Why the endpoint needs no permission. */
    String reason();
}
