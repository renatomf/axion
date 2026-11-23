package dev.axion.core.webhooks;

import dev.axion.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a webhook endpoint: its primary key, a UUIDv7, shown by the API as {@code we_…}. */
public record WebhookEndpointId(UUID value) {

    public static final String PREFIX = "we";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
