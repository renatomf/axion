package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.EndpointView;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

record WebhookEndpointResponse(
        String id,
        String object,
        String url,
        @Nullable String description,
        @JsonProperty("enabled_events") List<String> enabledEvents,
        String status,
        @JsonProperty("disabled_reason") @Nullable String disabledReason,
        @JsonProperty("created_at") Instant createdAt) {

    static WebhookEndpointResponse of(EndpointView endpoint) {
        return new WebhookEndpointResponse(
                endpoint.id().publicId().toString(),
                "webhook_endpoint",
                endpoint.url(),
                endpoint.description().orElse(null),
                // Sorted, so the response does not depend on set iteration order.
                endpoint.enabledEvents().stream().sorted().toList(),
                endpoint.status().name().toLowerCase(Locale.ROOT),
                endpoint.disabledReason()
                        .map(reason -> reason.name().toLowerCase(Locale.ROOT))
                        .orElse(null),
                endpoint.createdAt());
    }
}
