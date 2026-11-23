package dev.axion.core.access;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * A member revoked an API key (events and webhooks design, section 3.3). Revoking it again changes
 * nothing and publishes nothing. A security event, audited only.
 */
public record ApiKeyRevoked(EventHeader header, ApiKeyView key) implements DomainEvent {}
