package dev.axion.core.shared;

import java.util.UUID;

/**
 * A domain event's identifier (events and webhooks design, section 4.1): a UUIDv7 generated when
 * the event is created, the ID of its public event and the key every consumer deduplicates by.
 */
public record EventId(UUID value) {

    public static final String PREFIX = "evt";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
