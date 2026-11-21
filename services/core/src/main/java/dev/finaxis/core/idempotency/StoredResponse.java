package dev.finaxis.core.idempotency;

/**
 * The response a key holds, for a replay.
 *
 * @param correlationId of the request that ran
 */
public record StoredResponse(CapturedResponse response, String correlationId) {}
