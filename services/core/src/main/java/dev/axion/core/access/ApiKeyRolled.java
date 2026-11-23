package dev.axion.core.access;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * A member rolled an API key (events and webhooks design, section 3.3): one event for the key issued
 * and for the end scheduled of the key it replaces. A security event, audited only.
 *
 * @param rolled the old key, which works until its {@code expiresAt}
 */
public record ApiKeyRolled(EventHeader header, ApiKeyView replacement, ApiKeyView rolled) implements DomainEvent {}
