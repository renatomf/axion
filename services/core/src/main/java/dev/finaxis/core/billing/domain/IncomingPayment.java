package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.shared.Money;
import java.time.Instant;

/**
 * A Pix credit for a charge, as the rails reported it.
 *
 * @param endToEndId the payment's identity on the rails
 * @param acceptedAt when the rails settled it, which decides lateness, not when the core heard of it
 */
public record IncomingPayment(PaymentId id, String endToEndId, Money amount, Instant acceptedAt) {}
