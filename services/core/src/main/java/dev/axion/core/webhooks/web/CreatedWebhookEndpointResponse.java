package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.CreatedEndpoint;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

// The only place an endpoint's secret leaves the core, besides rolling it. A repeat of the request
// is answered without it: the secret is shown once.
record CreatedWebhookEndpointResponse(
        String id,
        String object,
        String url,
        @Nullable String description,
        @JsonProperty("enabled_events") List<String> enabledEvents,
        String status,
        @JsonProperty("disabled_reason") @Nullable String disabledReason,
        @JsonProperty("created_at") Instant createdAt,
        @Nullable String secret) {

    static CreatedWebhookEndpointResponse of(CreatedEndpoint created) {
        WebhookEndpointResponse endpoint = WebhookEndpointResponse.of(created.endpoint());
        return new CreatedWebhookEndpointResponse(
                endpoint.id(),
                endpoint.object(),
                endpoint.url(),
                endpoint.description(),
                endpoint.enabledEvents(),
                endpoint.status(),
                endpoint.disabledReason(),
                endpoint.createdAt(),
                created.secret());
    }

    CreatedWebhookEndpointResponse withoutSecret() {
        return new CreatedWebhookEndpointResponse(
                id, object, url, description, enabledEvents, status, disabledReason, createdAt, null);
    }

    // The generated toString would print the secret.
    @Override
    public String toString() {
        return "CreatedWebhookEndpointResponse[id=" + id + ", secret=…]";
    }
}
