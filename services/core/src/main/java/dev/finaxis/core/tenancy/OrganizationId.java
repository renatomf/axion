package dev.finaxis.core.tenancy;

import dev.finaxis.core.shared.PublicId;
import java.util.UUID;

/**
 * The identifier of an organization: a business that uses the platform, with one tenant per
 * environment (tenancy design, section 3.1).
 *
 * @param value the organization's primary key, a UUIDv7
 */
public record OrganizationId(UUID value) {

    /** The prefix of an organization's public ID, as in {@code org_01jd8x3k9vfz4t2p6q5w7r8s0a}. */
    public static final String PREFIX = "org";

    /** The ID as the API shows it. */
    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
