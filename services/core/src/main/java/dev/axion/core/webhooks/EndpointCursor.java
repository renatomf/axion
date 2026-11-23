package dev.axion.core.webhooks;

import java.util.Optional;

/** Where a page of {@link WebhooksApi#endpoints} starts: after an endpoint, not at a page number. */
public record EndpointCursor(Optional<WebhookEndpointId> after) {

    public static EndpointCursor first() {
        return new EndpointCursor(Optional.empty());
    }

    public static EndpointCursor after(WebhookEndpointId last) {
        return new EndpointCursor(Optional.of(last));
    }
}
