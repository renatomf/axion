package dev.axion.core.webhooks;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;
import java.time.Instant;

/**
 * An endpoint kept failing and was disabled (events and webhooks design, sections 3.3 and 8.6): it
 * receives nothing until the tenant enables it again. Audited, and shown on the tenant's live stream
 * as {@code webhook_endpoint.disabled}, so the tenant sees why its deliveries stopped.
 *
 * @param endpoint the endpoint as it is once disabled
 * @param failingSince when its first attempt failed since its last success
 */
public record WebhookEndpointDisabled(EventHeader header, EndpointView endpoint, Instant failingSince)
        implements DomainEvent {}
