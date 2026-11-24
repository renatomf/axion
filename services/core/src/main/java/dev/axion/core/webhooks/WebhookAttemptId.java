package dev.axion.core.webhooks;

import dev.axion.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a delivery's attempt: its primary key, a UUIDv7, shown by the API as {@code wha_…}. */
public record WebhookAttemptId(UUID value) {

    public static final String PREFIX = "wha";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
