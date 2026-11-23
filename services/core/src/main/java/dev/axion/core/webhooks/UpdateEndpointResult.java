package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.UrlRefusal;

/** The outcome of {@link WebhooksApi#update}. */
public sealed interface UpdateEndpointResult {

    /** The endpoint as it is now. */
    record Updated(EndpointView endpoint) implements UpdateEndpointResult {}

    record EndpointNotFound() implements UpdateEndpointResult {}

    /** The new URL failed the rule the reason names (events and webhooks design, section 7.1). */
    record InvalidUrl(UrlRefusal reason) implements UpdateEndpointResult {}
}
