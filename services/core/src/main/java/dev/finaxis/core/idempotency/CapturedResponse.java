package dev.finaxis.core.idempotency;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What an operation answered: the parts of its HTTP response a replay repeats (idempotency design,
 * section 3.4). The body is kept as written, byte for byte.
 */
public final class CapturedResponse {

    private final int status;
    private final String contentType;
    private final @Nullable String location;
    private final byte[] body;

    public CapturedResponse(int status, String contentType, @Nullable String location, byte[] body) {
        if (status < 100 || status > 599) {
            throw new IllegalArgumentException("Not an HTTP status: " + status);
        }
        if (contentType.isBlank()) {
            throw new IllegalArgumentException("A response has a content type");
        }
        this.status = status;
        this.contentType = contentType;
        this.location = location;
        this.body = body.clone();
    }

    public int status() {
        return status;
    }

    public String contentType() {
        return contentType;
    }

    public Optional<String> location() {
        return Optional.ofNullable(location);
    }

    public byte[] body() {
        return body.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CapturedResponse response
                && status == response.status
                && contentType.equals(response.contentType)
                && Objects.equals(location, response.location)
                && Arrays.equals(body, response.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, contentType, location, Arrays.hashCode(body));
    }

    // The body's length only: it may hold personal data, which logs never carry.
    @Override
    public String toString() {
        return "CapturedResponse[status=" + status + ", contentType=" + contentType + ", location=" + location
                + ", body=" + body.length + " bytes]";
    }
}
