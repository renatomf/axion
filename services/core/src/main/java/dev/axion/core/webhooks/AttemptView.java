package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.Attempt;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * One request sent for a delivery, as the delivery log recorded it (events and webhooks design,
 * section 8.7).
 *
 * @param responseExcerpt at most 1 KiB of the receiver's answer, as text: the tenant's server
 *     talking, never to be interpreted
 */
public record AttemptView(
        WebhookAttemptId id, Instant startedAt, Duration duration, Attempt outcome, Optional<String> responseExcerpt) {}
