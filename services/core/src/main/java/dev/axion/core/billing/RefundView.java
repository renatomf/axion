package dev.axion.core.billing;

import dev.axion.core.shared.Money;
import java.time.Instant;

/** A refund, as other modules see it (billing design, section 10.1). */
public record RefundView(
        RefundId id, ChargeId charge, PaymentId payment, Money amount, RefundStatus status, Instant createdAt) {}
