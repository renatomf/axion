package dev.axion.core.notifications.application;

import dev.axion.core.events.PublicEvent;

/** What a live stream sends its client (events and webhooks design, section 10). */
public sealed interface StreamMessage {

    /** A public event of the tenant, as the catalog wrote it. */
    record Event(PublicEvent event) implements StreamMessage {}
}
