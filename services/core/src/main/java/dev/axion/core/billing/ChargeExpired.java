package dev.axion.core.billing;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * An active charge expired unpaid (events and webhooks design, section 3.3), by the expiry job or by
 * a payment that arrived after its expiry.
 *
 * @param charge as it is once expired
 */
public record ChargeExpired(EventHeader header, ChargeDetail charge) implements DomainEvent {}
