package dev.axion.core.webhooks.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A delivery a worker holds for its next scheduled attempt, which is attempt {@code attemptCount + 1}. */
public record ClaimedDelivery(
        UUID id,
        UUID tenant,
        UUID endpoint,
        UUID event,
        URI url,
        String body,
        List<byte[]> secrets,
        int attemptCount,
        Instant leasedUntil)
        implements Claim {

    @Override
    public UUID delivery() {
        return id;
    }
}
