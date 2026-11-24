package dev.axion.core.webhooks;

import java.time.Duration;
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

    /**
     * Changes one of the tenant's endpoints, publishing {@link WebhookEndpointUpdated}; changes that
     * leave it as it was change nothing and publish nothing. A URL in the changes is checked before
     * anything is stored, as for a registration, even when it is the endpoint's own. Disabling an
     * endpoint cancels its pending deliveries and replays, so enabled again it receives only later
     * events and the replays asked for afresh.
     *
     * @throws IllegalArgumentException for a URL, description or event types outside the limits of
     *     {@link #create}
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    UpdateEndpointResult update(WebhookEndpointId id, EndpointChanges changes);

    /**
     * Deletes one of the tenant's endpoints, publishing {@link WebhookEndpointDeleted}: it receives
     * nothing from then on, its pending deliveries and replays canceled, is never read or listed
     * again, and no longer counts toward {@link #MAX_ENDPOINTS}.
     *
     * @return the endpoint as it was, or empty for an unknown endpoint, another tenant's and one
     *     already deleted alike
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<EndpointView> delete(WebhookEndpointId id);

    /**
     * Issues a new signing secret for one of the tenant's endpoints, publishing {@link
     * WebhookSecretRolled}. The secret it replaces keeps signing for the grace period, so the tenant
     * can deploy the new one first; a grace period of zero ends it at once.
     *
     * @throws IllegalArgumentException if the grace period is outside zero to {@link
     *     #MAX_SECRET_GRACE}
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    RollSecretResult rollSecret(WebhookEndpointId id, Duration grace);

    /**
     * A page of one endpoint's delivery log, newest first, with the deliveries the filter keeps. The
     * log of a deleted endpoint stays readable until its retention ends (events and webhooks design,
     * section 6.3). Start from {@link DeliveryCursor#first()}, then each page's {@link
     * DeliveryPage#next()}.
     *
     * @param limit 1 to {@value #MAX_PAGE_SIZE}
     * @return empty for an unknown endpoint and for another tenant's alike
     * @throws IllegalArgumentException if the limit is out of range
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<DeliveryPage> deliveries(
            WebhookEndpointId endpoint, DeliveryFilter filter, DeliveryCursor cursor, int limit);

    /**
     * One delivery of an endpoint's log, with every request sent for it.
     *
     * @return empty for an unknown delivery, one of another endpoint, and one of another tenant alike
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<DeliveryDetail> delivery(WebhookEndpointId endpoint, WebhookDeliveryId id);

    /**
     * Queues one of an endpoint's deliveries to be sent again, now: a worker sends the same event and
     * body with a fresh timestamp and signature, and records the request in the log as a replay. A
     * delivery in any status can be replayed; the replay changes neither its status nor its schedule.
     *
     * @return {@link ReplayResult.DeliveryNotFound} for an unknown delivery, one of another endpoint,
     *     of a deleted endpoint and of another tenant alike
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    ReplayResult replay(WebhookEndpointId endpoint, WebhookDeliveryId id);

    /** The most endpoints, or deliveries, one page of {@link #endpoints} or {@link #deliveries} holds. */
    int MAX_PAGE_SIZE = 100;

    int MAX_ENDPOINTS = 16;

    int MAX_URL_LENGTH = 2_048;

    int MAX_DESCRIPTION_LENGTH = 140;

    int MAX_EVENT_TYPES = 20;

    Duration MAX_SECRET_GRACE = Duration.ofDays(7);

    Duration DEFAULT_SECRET_GRACE = Duration.ofHours(24);

    /** The most times one delivery is replayed within {@link #REPLAY_WINDOW}. */
    int MAX_REPLAYS = 10;

    Duration REPLAY_WINDOW = Duration.ofDays(1);
}
