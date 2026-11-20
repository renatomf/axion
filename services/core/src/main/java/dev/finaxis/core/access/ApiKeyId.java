package dev.finaxis.core.access;

import dev.finaxis.core.shared.PublicId;
import java.util.UUID;

/** An API key's identifier; unlike its secret, it grants nothing. */
public record ApiKeyId(UUID value) {

    public static final String PREFIX = "key";

    public PublicId publicId() {
        return new PublicId(PREFIX, value);
    }
}
