package dev.finaxis.core.billing;

import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.shared.Money;
import java.util.Optional;

/**
 * A refund to make of one payment of a charge (billing design, section 10.1).
 *
 * @param payment the payment to refund; may be empty only when the charge has one
 * @param amount what to return; empty to return all that remains of the payment
 * @param correlationId of the request, recorded with the refund and its entry
 */
public record RefundRequest(Optional<PaymentId> payment, Optional<Money> amount, String correlationId) {

    public RefundRequest {
        amount.ifPresent(value -> {
            if (!value.isPositive() || !value.currency().equals(Charge.CURRENCY)) {
                throw new IllegalArgumentException(
                        "A refund is a positive amount in " + Charge.CURRENCY + ", not " + value);
            }
        });
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("A refund names the request that made it");
        }
    }
}
