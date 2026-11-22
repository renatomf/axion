package dev.finaxis.core.billing;

import dev.finaxis.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a charge: its primary key, a UUIDv7, shown by the API as {@code ch_…}. */
public record ChargeId(UUID value) {

    public static final String PREFIX = "ch";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
