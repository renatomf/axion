package dev.axion.core.access;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/** A member issued an API key (events and webhooks design, section 3.3); a security event, audited only. */
public record ApiKeyIssued(EventHeader header, ApiKeyView key) implements DomainEvent {}
