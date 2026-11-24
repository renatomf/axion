package dev.axion.core.webhooks.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A delivery a worker holds until {@code leasedUntil}, with what it sends: the event's body to the
 * endpoint's URL, signed with the endpoint's secrets.
 *
 * @param body the event as stored, UTF-8 JSON written once by the catalog
 * @param secrets the ciphertexts of the endpoint's secrets that sign, the current one first and a
 *     rolled one in its grace period after it
 */
public record ClaimedDelivery(
        UUID id,
        UUID endpoint,
        UUID event,
        URI url,
        String body,
        List<byte[]> secrets,
        int attemptCount,
        Instant leasedUntil) {}
