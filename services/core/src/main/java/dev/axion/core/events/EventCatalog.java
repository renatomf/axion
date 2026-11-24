package dev.axion.core.events;

import dev.axion.core.shared.DomainEvent;
import java.util.Optional;
import java.util.Set;

/**
 * The public events of the core (events and webhooks design, sections 3.3 and 5): which domain
 * events leave the core, under which type, and how they are written. The only place a public event
 * is built, so a tenant's server and its console read the same payload.
 */
public interface EventCatalog {

    /**
     * The domain event as a CloudEvent; empty for an event with no public type, such as a security
     * event. A pure function of the event: it reads no database, so a consumer that calls it cannot
     * be slowed down by it.
     */
    Optional<PublicEvent> render(DomainEvent event);

    /** Every public type, such as {@code charge.paid}. */
    Set<String> publicTypes();
}
