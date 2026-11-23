package dev.axion.core.webhooks.application;

import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the delivery queue every second (events and webhooks design, section 8.2). Off when {@code
 * axion.webhooks.delivery.enabled} is false, so tests drain it themselves.
 */
@Component
@ConditionalOnBooleanProperty(name = "axion.webhooks.delivery.enabled", matchIfMissing = true)
class ScheduledDelivery {

    private final DeliveryQueue queue;

    ScheduledDelivery(DeliveryQueue queue) {
        this.queue = queue;
    }

    @Scheduled(initialDelay = 1, fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void drain() {
        queue.drain();
    }
}
