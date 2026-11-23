package dev.axion.core.webhooks;

/**
 * The webhook endpoints of the bound tenant (events and webhooks design, section 6). Calls run in the
 * caller's transaction when there is one; changes publish their events in it, with the actor of the
 * current work.
 */
public interface WebhooksApi {

    /**
     * Registers an endpoint, with a new signing secret, publishing {@link WebhookEndpointCreated}.
     * The URL is checked before anything is stored, and its name resolved, so a refused URL leaves
     * nothing behind.
     *
     * @throws IllegalArgumentException for a URL, description or event types outside the request's
     *     limits
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    CreateEndpointResult create(EndpointRequest request);

    int MAX_ENDPOINTS = 16;

    int MAX_URL_LENGTH = 2_048;

    int MAX_DESCRIPTION_LENGTH = 140;

    int MAX_EVENT_TYPES = 20;
}
