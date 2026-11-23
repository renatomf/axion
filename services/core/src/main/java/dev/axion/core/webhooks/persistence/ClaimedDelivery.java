package dev.axion.core.webhooks.persistence;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

/**
 * A delivery a worker holds until {@code leasedUntil}, with what it sends: the event's body to the
 * endpoint's URL.
 *
 * @param body the event as stored, UTF-8 JSON written once by the catalog
 */
public record ClaimedDelivery(
        UUID id, UUID endpoint, UUID event, URI url, String body, int attemptCount, Instant leasedUntil) {}
