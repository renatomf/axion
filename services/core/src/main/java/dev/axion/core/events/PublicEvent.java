package dev.axion.core.events;

import dev.axion.core.shared.EventId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * A domain event as it leaves the core (events and webhooks design, section 5.1).
 *
 * @param id the domain event's, the same for every delivery and replay of it
 * @param type its public type, such as {@code charge.paid}
 * @param occurredAt the domain event's
 * @param body the CloudEvent in structured JSON mode, written once: every delivery of the event
 *     sends these exact bytes, so a signature verified once stays valid evidence
 */
public record PublicEvent(EventId id, String type, Instant occurredAt, String body) {

    public byte[] bodyBytes() {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
