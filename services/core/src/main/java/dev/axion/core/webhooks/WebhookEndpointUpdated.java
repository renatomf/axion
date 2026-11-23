package dev.axion.core.webhooks;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * An endpoint was changed (events and webhooks design, section 3.3); a security event, audited only.
 * It carries the endpoint before and after, so the audit trail shows where its notifications went.
 */
public record WebhookEndpointUpdated(EventHeader header, EndpointView endpoint, EndpointView previous)
        implements DomainEvent {}
