package dev.axion.core.webhooks;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;
import java.time.Instant;

/**
 * An endpoint's signing secret was rolled (events and webhooks design, section 3.3); a security
 * event, audited only. It never carries a secret.
 *
 * @param previousSecretExpiresAt when the replaced secret stops signing
 */
public record WebhookSecretRolled(EventHeader header, EndpointView endpoint, Instant previousSecretExpiresAt)
        implements DomainEvent {}
