package dev.finaxis.core.access.web;

import dev.finaxis.core.access.RollResult;

/**
 * A {@code RolledApiKey} of {@code openapi/internal.yaml}: the replacement of a rolled key, with its
 * secret, shown this once, and the old key as lists show it, whose {@code expires_at} is the end of
 * its grace period.
 *
 * <p>The generated {@code toString} prints both components, and {@link IssuedApiKeyResponse} masks
 * its secret in its own, so this record needs no override.
 */
record RolledApiKeyResponse(IssuedApiKeyResponse replacement, ApiKeyResponse rolled) {

    static RolledApiKeyResponse of(RollResult.Rolled rolled) {
        return new RolledApiKeyResponse(
                IssuedApiKeyResponse.of(rolled.replacement()), ApiKeyResponse.of(rolled.rolled()));
    }
}
