package dev.axion.core.webhooks;

import java.util.Optional;

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

    /**
     * A page of the tenant's endpoints, newest first, never with their secrets. Start from {@link
     * EndpointCursor#first()}, then each page's {@link EndpointPage#next()}.
     *
     * @param limit 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException if the limit is out of range
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    EndpointPage endpoints(EndpointCursor cursor, int limit);

    /**
     * One of the tenant's endpoints, without its secret; empty for an unknown endpoint and for another
     * tenant's alike.
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<EndpointView> endpoint(WebhookEndpointId id);

    /** The most endpoints one page of {@link #endpoints} holds. */
    int MAX_PAGE_SIZE = 100;

    int MAX_ENDPOINTS = 16;

    int MAX_URL_LENGTH = 2_048;

    int MAX_DESCRIPTION_LENGTH = 140;

    int MAX_EVENT_TYPES = 20;
}
