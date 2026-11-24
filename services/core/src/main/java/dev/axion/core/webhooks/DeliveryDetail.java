package dev.axion.core.webhooks;

import java.util.List;

/** A delivery with every request sent for it, oldest first. */
public record DeliveryDetail(DeliveryView delivery, List<AttemptView> attempts) {

    public DeliveryDetail {
        attempts = List.copyOf(attempts);
    }
}
