package dev.axion.core.webhooks;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * An endpoint was deleted (events and webhooks design, section 3.3); a security event, audited only.
 *
 * @param endpoint the endpoint as it was when it was deleted
 */
public record WebhookEndpointDeleted(EventHeader header, EndpointView endpoint) implements DomainEvent {}
