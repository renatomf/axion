package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.DeliveryDetail;
import dev.axion.core.webhooks.DeliveryView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

record WebhookDeliveryDetailResponse(
        String id,
        String object,
        String endpoint,
        String event,
        @JsonProperty("event_type") String eventType,
        String status,
        @JsonProperty("attempt_count") int attemptCount,
        @JsonProperty("next_attempt_at") @Nullable Instant nextAttemptAt,
        @JsonProperty("created_at") Instant createdAt,
        List<WebhookAttemptResponse> attempts) {

    static WebhookDeliveryDetailResponse of(DeliveryDetail detail) {
        DeliveryView delivery = detail.delivery();
        return new WebhookDeliveryDetailResponse(
                delivery.id().publicId().toString(),
                "webhook_delivery",
                delivery.endpoint().publicId().toString(),
                delivery.event().publicId().toString(),
                delivery.eventType(),
                delivery.status().name().toLowerCase(Locale.ROOT),
                delivery.attemptCount(),
                delivery.nextAttemptAt().orElse(null),
                delivery.createdAt(),
                detail.attempts().stream().map(WebhookAttemptResponse::of).toList());
    }
}
