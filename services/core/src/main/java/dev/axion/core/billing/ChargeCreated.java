package dev.axion.core.billing;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/** A charge was created; it has no payment yet (events and webhooks design, section 3.3). */
public record ChargeCreated(EventHeader header, ChargeDetail charge) implements DomainEvent {}
