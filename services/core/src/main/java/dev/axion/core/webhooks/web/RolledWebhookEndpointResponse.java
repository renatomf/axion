package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.RolledSecret;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

// The endpoint with its new secret, shown once like a registration's: a repeat of the request is
// answered without it.
record RolledWebhookEndpointResponse(
        String id,
        String object,
        String url,
        @Nullable String description,
        @JsonProperty("enabled_events") List<String> enabledEvents,
        String status,
        @JsonProperty("disabled_reason") @Nullable String disabledReason,
        @JsonProperty("created_at") Instant createdAt,
        @Nullable String secret,
        @JsonProperty("previous_secret_expires_at") Instant previousSecretExpiresAt) {

    static RolledWebhookEndpointResponse of(RolledSecret rolled) {
        WebhookEndpointResponse endpoint = WebhookEndpointResponse.of(rolled.endpoint());
        return new RolledWebhookEndpointResponse(
                endpoint.id(),
                endpoint.object(),
                endpoint.url(),
                endpoint.description(),
                endpoint.enabledEvents(),
                endpoint.status(),
                endpoint.disabledReason(),
                endpoint.createdAt(),
                rolled.secret(),
                rolled.previousSecretExpiresAt());
    }

    RolledWebhookEndpointResponse withoutSecret() {
        return new RolledWebhookEndpointResponse(
                id,
                object,
                url,
                description,
                enabledEvents,
                status,
                disabledReason,
                createdAt,
                null,
                previousSecretExpiresAt);
    }

    // The generated toString would print the secret.
    @Override
    public String toString() {
        return "RolledWebhookEndpointResponse[id=" + id + ", secret=…]";
    }
}
