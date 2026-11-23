package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.EndpointPage;
import java.util.List;

record WebhookEndpointListResponse(
        String object,
        List<WebhookEndpointResponse> data,
        @JsonProperty("has_more") boolean hasMore) {

    static WebhookEndpointListResponse of(EndpointPage page) {
        return new WebhookEndpointListResponse(
                "list",
                page.endpoints().stream().map(WebhookEndpointResponse::of).toList(),
                page.hasMore());
    }
}
