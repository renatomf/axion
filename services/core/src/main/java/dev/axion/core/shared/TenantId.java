package dev.axion.core.shared;

import java.util.UUID;

/**
 * The unit of data isolation: one environment of one organization (tenancy design, section 3.2).
 * Every tenant-owned row carries it.
 */
public record TenantId(UUID value) {

    public static final String PREFIX = "ten";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
