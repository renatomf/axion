package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.UrlRefusal;

/** The outcome of {@link WebhooksApi#create}. */
public sealed interface CreateEndpointResult {

    record Created(CreatedEndpoint endpoint) implements CreateEndpointResult {}

    /** The URL failed the rule the reason names (events and webhooks design, section 7.1). */
    record InvalidUrl(UrlRefusal reason) implements CreateEndpointResult {}

    /** The tenant already has {@value WebhooksApi#MAX_ENDPOINTS} endpoints. */
    record LimitReached(int limit) implements CreateEndpointResult {}
}
