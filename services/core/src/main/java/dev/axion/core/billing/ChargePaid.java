package dev.axion.core.billing;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * A payment settled the charge (events and webhooks design, section 3.3): once per charge, also for
 * a charge that had expired when a payment made in time arrived.
 *
 * @param charge as it is after settlement
 */
public record ChargePaid(EventHeader header, ChargeDetail charge) implements DomainEvent {}
