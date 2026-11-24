package dev.axion.core.webhooks;

import java.util.Optional;

/** Where a page of {@link WebhooksApi#deliveries} starts: after a delivery, not at a page number. */
public record DeliveryCursor(Optional<WebhookDeliveryId> after) {

    public static DeliveryCursor first() {
        return new DeliveryCursor(Optional.empty());
    }

    public static DeliveryCursor after(WebhookDeliveryId last) {
        return new DeliveryCursor(Optional.of(last));
    }
}
