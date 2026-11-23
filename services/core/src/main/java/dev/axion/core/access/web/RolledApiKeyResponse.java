package dev.axion.core.access.web;

import dev.axion.core.access.RollResult;

record RolledApiKeyResponse(IssuedApiKeyResponse replacement, ApiKeyResponse rolled) {

    static RolledApiKeyResponse of(RollResult.Rolled rolled) {
        return new RolledApiKeyResponse(
                IssuedApiKeyResponse.of(rolled.replacement()), ApiKeyResponse.of(rolled.rolled()));
    }
}
