package dev.axion.core.billing;

import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.EventHeader;

/**
 * A payment was credited to the tenant without settling its charge, for the reason it carries
 * (events and webhooks design, section 3.3).
 *
 * @param charge as it is after the payment
 */
public record PaymentFlagged(EventHeader header, PaymentView payment, ChargeDetail charge) implements DomainEvent {}
