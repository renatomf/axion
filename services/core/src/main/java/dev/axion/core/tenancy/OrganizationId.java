package dev.axion.core.tenancy;

import dev.axion.core.shared.PublicId;
import java.util.UUID;

/** A business that uses the platform, with one tenant per environment (tenancy design, section 3.1). */
public record OrganizationId(UUID value) {

    public static final String PREFIX = "org";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
