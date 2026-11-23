package dev.axion.core.billing;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * A payment of the charge was refunded, the settling one or a flagged one (events and webhooks
 * design, section 3.3).
 *
 * @param charge as it is after the refund
 */
public record ChargeRefunded(EventHeader header, RefundView refund, ChargeDetail charge) implements DomainEvent {}
