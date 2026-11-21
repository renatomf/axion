package dev.finaxis.core.idempotency;

import dev.finaxis.core.shared.TenantId;

/**
 * A request to run once per key.
 *
 * @param tenant the tenant of the caller's credential, which owns the key
 * @param correlationId of this request; stored with the response, so replays name the request that ran
 */
public record IdempotentRequest(TenantId tenant, IdempotencyKey key, Fingerprint fingerprint, String correlationId) {}
