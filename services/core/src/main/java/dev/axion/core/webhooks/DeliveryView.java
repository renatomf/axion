package dev.axion.core.webhooks;

import dev.axion.core.shared.EventId;
import java.time.Instant;
import java.util.Optional;

/**
 * One public event sent to one endpoint, as the delivery log shows it (events and webhooks design,
 * section 8.7).
 *
 * @param eventType the event's public type, such as {@code charge.paid}
 * @param attemptCount the scheduled attempts made so far, 0 to 9
 * @param nextAttemptAt when the next attempt is due; empty once the delivery is no longer pending
 */
public record DeliveryView(
        WebhookDeliveryId id,
        WebhookEndpointId endpoint,
        EventId event,
        String eventType,
        DeliveryStatus status,
        int attemptCount,
        Optional<Instant> nextAttemptAt,
        Instant createdAt) {}
