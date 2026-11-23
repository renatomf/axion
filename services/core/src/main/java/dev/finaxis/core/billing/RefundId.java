package dev.finaxis.core.billing;

import dev.finaxis.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a refund: a UUIDv7, shown by the API as {@code re_…}. */
public record RefundId(UUID value) {

    public static final String PREFIX = "re";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
