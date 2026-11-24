package dev.axion.core.webhooks;

import java.time.Instant;

/** The outcome of {@link WebhooksApi#replay}. */
public sealed interface ReplayResult {

    /** The replay was queued; {@code delivery} is the delivery as it is, which the replay does not change. */
    record Queued(DeliveryView delivery) implements ReplayResult {}

    record DeliveryNotFound() implements ReplayResult {}

    /** The endpoint receives nothing while disabled; enabled again, its deliveries can be replayed. */
    record EndpointDisabled() implements ReplayResult {}

    /**
     * The delivery was replayed {@link WebhooksApi#MAX_REPLAYS} times within {@link
     * WebhooksApi#REPLAY_WINDOW}; it can be replayed again from {@code retryAt}.
     */
    record LimitReached(Instant retryAt) implements ReplayResult {}
}
