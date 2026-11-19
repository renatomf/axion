package dev.finaxis.core.access;

import dev.finaxis.core.shared.PublicId;
import java.util.UUID;

/**
 * The identifier of an API key (tenancy design, section 7). It names a key in lists, logs and the
 * endpoints that roll or revoke it; unlike the key's secret, it grants nothing.
 *
 * @param value the key's primary key, a UUIDv7
 */
public record ApiKeyId(UUID value) {

    /** The prefix of an API key's public ID, as in {@code key_01jd8x3k9vfz4t2p6q5w7r8s0c}. */
    public static final String PREFIX = "key";

    /** The ID as the API shows it. */
    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
