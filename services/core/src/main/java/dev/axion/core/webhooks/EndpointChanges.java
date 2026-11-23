package dev.axion.core.webhooks;

import java.util.Optional;
import java.util.Set;

/**
 * Changes to an endpoint (events and webhooks design, section 6.3). A field left empty keeps its
 * value; the limits are those of {@link EndpointRequest}.
 *
 * @param url a new URL, checked again as section 7.1 describes
 * @param description present to change the description: the new one, or empty to remove it
 * @param status {@code ENABLED} clears the reason the endpoint was disabled for; {@code DISABLED}
 *     disables an enabled endpoint by the tenant, and leaves a disabled one with its reason
 */
public record EndpointChanges(
        Optional<String> url,
        Optional<Optional<String>> description,
        Optional<Set<String>> enabledEvents,
        Optional<EndpointStatus> status) {

    public EndpointChanges {
        enabledEvents = enabledEvents.map(Set::copyOf);
    }
}
