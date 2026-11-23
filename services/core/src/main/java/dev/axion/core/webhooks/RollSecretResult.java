package dev.axion.core.webhooks;

import java.time.Instant;

/** The outcome of {@link WebhooksApi#rollSecret}. */
public sealed interface RollSecretResult {

    record Rolled(RolledSecret secret) implements RollSecretResult {}

    record EndpointNotFound() implements RollSecretResult {}

    /**
     * The previous roll's grace period has not ended: an endpoint never has more than two secrets
     * that sign, so it rolls again only from {@code graceEndsAt}.
     */
    record GracePeriodActive(Instant graceEndsAt) implements RollSecretResult {}
}
