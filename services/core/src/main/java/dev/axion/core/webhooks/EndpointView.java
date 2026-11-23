package dev.axion.core.webhooks;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * A webhook endpoint as it is now, without its secret, which is shown only once (events and
 * webhooks design, section 6.1).
 *
 * @param enabledEvents the public event types it receives, or {@code *} alone for every type
 */
public record EndpointView(
        WebhookEndpointId id,
        String url,
        Optional<String> description,
        Set<String> enabledEvents,
        EndpointStatus status,
        Optional<DisabledReason> disabledReason,
        Instant createdAt) {

    public EndpointView {
        enabledEvents = Set.copyOf(enabledEvents);
    }
}
