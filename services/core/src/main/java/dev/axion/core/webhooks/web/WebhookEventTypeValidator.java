package dev.axion.core.webhooks.web;

import dev.axion.core.webhooks.domain.EventTypes;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.jspecify.annotations.Nullable;

// Public: Hibernate Validator instantiates it by reflection.
public final class WebhookEventTypeValidator implements ConstraintValidator<WebhookEventType, String> {

    @Override
    public boolean isValid(@Nullable String value, ConstraintValidatorContext context) {
        return value == null || value.equals(EventTypes.ALL) || EventTypes.WEBHOOK.contains(value);
    }
}
