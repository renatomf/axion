package dev.axion.core.webhooks.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An event type an endpoint can receive, or {@code *}, read from {@link
 * dev.axion.core.webhooks.domain.EventTypes}. Placed on list elements, so a failure names the
 * element, such as {@code enabled_events[1]}.
 */
@Documented
@Constraint(validatedBy = WebhookEventTypeValidator.class)
@Target(ElementType.TYPE_USE)
@Retention(RetentionPolicy.RUNTIME)
@interface WebhookEventType {

    String message() default "is not an event type endpoints can receive";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
