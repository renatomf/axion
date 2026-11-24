package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.DeliveryPage;
import java.util.List;

record WebhookDeliveryListResponse(
        String object,
        List<WebhookDeliveryResponse> data,
        @JsonProperty("has_more") boolean hasMore) {

    static WebhookDeliveryListResponse of(DeliveryPage page) {
        return new WebhookDeliveryListResponse(
                "list",
                page.deliveries().stream().map(WebhookDeliveryResponse::of).toList(),
                page.hasMore());
    }
}
