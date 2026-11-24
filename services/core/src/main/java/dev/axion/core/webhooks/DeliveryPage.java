package dev.axion.core.webhooks;

import java.util.List;
import java.util.Optional;

/** One page of an endpoint's deliveries, newest first; {@code next} is empty on the last page. */
public record DeliveryPage(List<DeliveryView> deliveries, Optional<DeliveryCursor> next) {

    public DeliveryPage {
        deliveries = List.copyOf(deliveries);
    }

    public boolean hasMore() {
        return next.isPresent();
    }
}
