package dev.finaxis.core.access;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An operation that needs a login of the last five minutes, not just a valid session (tenancy
 * design, section 4.2). Without one, {@code 403 step_up_required} with an RFC 9470 challenge.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresStepUp {}
