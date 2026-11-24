package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.events.EventContract;
import dev.axion.core.events.PublicEventWriter;
import dev.axion.core.shared.PublicId;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Writes {@code webhook_endpoint.disabled} for the catalog (events and webhooks design, sections
 * 3.3 and 8.6): the endpoint as {@code GET /v1/webhook_endpoints/{id}} returns it once disabled, and
 * when it started failing. Only the console's live stream receives it, so the internal contract
 * describes it.
 */
@Component
class DisabledEndpointWriter implements PublicEventWriter<WebhookEndpointDisabled> {

    @Override
    public Class<WebhookEndpointDisabled> event() {
        return WebhookEndpointDisabled.class;
    }

    @Override
    public String type() {
        return "webhook_endpoint.disabled";
    }

    @Override
    public EventContract contract() {
        return EventContract.INTERNAL;
    }

    @Override
    public PublicId subject(WebhookEndpointDisabled event) {
        return event.endpoint().id().publicId();
    }

    @Override
    public Object data(WebhookEndpointDisabled event) {
        return new DisabledEndpoint(WebhookEndpointResponse.of(event.endpoint()), event.failingSince());
    }

    private record DisabledEndpoint(
            WebhookEndpointResponse object,
            @JsonProperty("failing_since") Instant failingSince) {}
}
