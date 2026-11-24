package dev.axion.core.webhooks;

import java.util.Optional;

/**
 * Which of an endpoint's deliveries a page of {@link WebhooksApi#deliveries} holds: those in the
 * status and of the event type given, or every one for a criterion left empty.
 */
public record DeliveryFilter(Optional<DeliveryStatus> status, Optional<String> eventType) {

    public static DeliveryFilter all() {
        return new DeliveryFilter(Optional.empty(), Optional.empty());
    }
}
