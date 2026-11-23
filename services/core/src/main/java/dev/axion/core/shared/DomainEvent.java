package dev.axion.core.shared;

import java.time.Instant;

/**
 * A fact a module publishes in the transaction of its change (events and webhooks design, section
 * 4.1). An event carries what its consumers need to act without calling back, and no secret.
 */
public interface DomainEvent {

    EventHeader header();

    default EventId id() {
        return header().id();
    }

    default TenantId tenant() {
        return header().tenant();
    }

    default Environment environment() {
        return header().environment();
    }

    default Actor actor() {
        return header().actor();
    }

    default String correlationId() {
        return header().correlationId();
    }

    default Instant occurredAt() {
        return header().occurredAt();
    }
}
