package dev.axion.core.billing;

import dev.axion.core.shared.PublicId;
import java.util.UUID;

/** The identifier of a payment a charge received: a UUIDv7, shown by the API as {@code pmt_…}. */
public record PaymentId(UUID value) {

    public static final String PREFIX = "pmt";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
