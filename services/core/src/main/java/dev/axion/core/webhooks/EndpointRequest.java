package dev.axion.core.webhooks;

import java.util.Optional;
import java.util.Set;

/**
 * An endpoint to register (events and webhooks design, section 6.1). The tenant is the one bound,
 * never a field of the request.
 *
 * @param url an {@code https} URL of at most {@value WebhooksApi#MAX_URL_LENGTH} characters, checked
 *     as section 7.1 describes when it is registered
 * @param description 1 to {@value WebhooksApi#MAX_DESCRIPTION_LENGTH} characters, if any
 * @param enabledEvents 1 to {@value WebhooksApi#MAX_EVENT_TYPES} public event types, or {@code *}
 *     alone for every type, including those added later
 */
public record EndpointRequest(String url, Optional<String> description, Set<String> enabledEvents) {

    public EndpointRequest {
        enabledEvents = Set.copyOf(enabledEvents);
    }
}
