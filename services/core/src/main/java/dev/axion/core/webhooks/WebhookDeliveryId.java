package dev.axion.core.webhooks;

import dev.axion.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a webhook delivery: its primary key, a UUIDv7, shown by the API as {@code whd_…}. */
public record WebhookDeliveryId(UUID value) {

    public static final String PREFIX = "whd";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
