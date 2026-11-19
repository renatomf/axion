package dev.finaxis.core.shared;

import java.util.UUID;

/**
 * The identifier of a tenant, the unit of data isolation: one environment of one organization
 * (tenancy design, section 3.2). Every tenant-owned row carries it.
 *
 * <p>It lives in {@code shared} because every module that stores tenant data needs it. A dedicated
 * type, instead of a bare {@link UUID}, keeps a tenant ID from being passed where an account or
 * charge ID is expected.
 *
 * @param value the tenant's primary key, a UUIDv7
 */
public record TenantId(UUID value) {

    /** The prefix of a tenant's public ID, as in {@code ten_01jd8x3k9vfz4t2p6q5w7r8s0b}. */
    public static final String PREFIX = "ten";

    /** The ID as the API shows it. */
    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
