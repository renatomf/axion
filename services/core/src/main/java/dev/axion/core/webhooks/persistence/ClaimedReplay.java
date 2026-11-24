package dev.axion.core.webhooks.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A replay a worker holds: its delivery's event sent once more, outside the delivery's schedule. */
public record ClaimedReplay(
        UUID id,
        UUID delivery,
        UUID tenant,
        UUID endpoint,
        UUID event,
        URI url,
        String body,
        List<byte[]> secrets,
        Instant leasedUntil)
        implements Claim {}
