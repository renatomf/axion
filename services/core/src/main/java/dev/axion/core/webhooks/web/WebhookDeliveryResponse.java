package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.DeliveryView;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

record WebhookDeliveryResponse(
        String id,
        String object,
        String endpoint,
        String event,
        @JsonProperty("event_type") String eventType,
        String status,
        @JsonProperty("attempt_count") int attemptCount,
        @JsonProperty("next_attempt_at") @Nullable Instant nextAttemptAt,
        @JsonProperty("created_at") Instant createdAt) {

    static WebhookDeliveryResponse of(DeliveryView delivery) {
        return new WebhookDeliveryResponse(
                delivery.id().publicId().toString(),
                "webhook_delivery",
                delivery.endpoint().publicId().toString(),
                delivery.event().publicId().toString(),
                delivery.eventType(),
                delivery.status().name().toLowerCase(Locale.ROOT),
                delivery.attemptCount(),
                delivery.nextAttemptAt().orElse(null),
                delivery.createdAt());
    }
}
