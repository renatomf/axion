package dev.axion.core.shared;

import java.time.Instant;

/**
 * What every domain event carries besides its own facts (events and webhooks design, section 4.1).
 *
 * @param tenant the tenant whose data changed
 * @param correlationId the request's, or the job run's
 * @param occurredAt from the injected clock, in the publishing transaction
 */
public record EventHeader(
        EventId id, TenantId tenant, Environment environment, Actor actor, String correlationId, Instant occurredAt) {

    public EventHeader {
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("An event names the request or job run that caused it");
        }
    }

    /**
     * The header of an event of the current work: the tenant from {@link TenantContext}, the actor
     * and correlation ID from {@link ActorContext}.
     *
     * @throws IllegalStateException if either is unbound: every change is made for a tenant, by someone
     */
    public static EventHeader ofCurrentWork(EventId id, Instant occurredAt) {
        TenantContext.Binding tenant =
                TenantContext.current().orElseThrow(() -> new IllegalStateException("An event needs a tenant bound"));
        ActorContext.Binding actor =
                ActorContext.current().orElseThrow(() -> new IllegalStateException("An event needs an actor bound"));
        return new EventHeader(
                id, tenant.tenant(), tenant.environment(), actor.actor(), actor.correlationId(), occurredAt);
    }
}
