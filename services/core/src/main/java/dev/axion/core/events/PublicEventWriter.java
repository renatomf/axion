package dev.axion.core.events;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.PublicId;

/**
 * How the catalog writes a public type of a module that consumes the catalog itself (events and
 * webhooks design, section 5.1). The catalog cannot read such a module's representations without a
 * cycle between the two, so the module contributes this writer as a bean; the catalog still builds
 * the envelope, and stays the only place a public event is built.
 *
 * <p>A writer is a pure function of the event, as the catalog is: it reads no database.
 *
 * @param <E> the domain event it writes
 */
public interface PublicEventWriter<E extends DomainEvent> {

    Class<E> event();

    /** The public type, such as {@code webhook_endpoint.disabled}. */
    String type();

    /** The document whose {@code webhooks} section holds the type's schema. */
    EventContract contract();

    /** The public ID of the resource the event is about. */
    PublicId subject(E event);

    /** The event's {@code data}, written with the core's JSON mapper. */
    Object data(E event);
}
