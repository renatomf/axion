package dev.axion.core.webhooks;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * An endpoint was registered (events and webhooks design, section 3.3); a security event, audited
 * only. It never carries the secret.
 */
public record WebhookEndpointCreated(EventHeader header, EndpointView endpoint) implements DomainEvent {}
