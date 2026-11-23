package dev.axion.core.billing.domain;

import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.RefundId;
import dev.axion.core.shared.Money;
import java.time.Instant;

/** Money given back from one payment of a charge, in full or in part. */
public record Refund(RefundId id, PaymentId payment, Money amount, Instant createdAt) {

    public Refund {
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A refund's amount is positive, not " + amount);
        }
    }
}
