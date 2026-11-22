package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.PaymentOutcome;
import dev.finaxis.core.billing.ReviewReason;
import dev.finaxis.core.shared.Money;
import java.time.Instant;
import java.util.Optional;

/** A payment a charge received, with its outcome; a flagged one always has its reason. */
public record Payment(
        PaymentId id,
        String endToEndId,
        Money amount,
        Instant acceptedAt,
        PaymentOutcome outcome,
        Optional<ReviewReason> reviewReason) {

    public Payment {
        if (endToEndId.isBlank()) {
            throw new IllegalArgumentException("A payment has an EndToEndId");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A payment's amount is positive, not " + amount);
        }
        if ((outcome == PaymentOutcome.FLAGGED) != reviewReason.isPresent()) {
            throw new IllegalArgumentException(
                    "A payment has a review reason exactly when it is flagged: " + outcome + ", " + reviewReason);
        }
    }

    static Payment settled(IncomingPayment payment) {
        return new Payment(
                payment.id(),
                payment.endToEndId(),
                payment.amount(),
                payment.acceptedAt(),
                PaymentOutcome.SETTLED,
                Optional.empty());
    }

    static Payment flagged(IncomingPayment payment, ReviewReason reason) {
        return new Payment(
                payment.id(),
                payment.endToEndId(),
                payment.amount(),
                payment.acceptedAt(),
                PaymentOutcome.FLAGGED,
                Optional.of(reason));
    }

    public boolean isSettled() {
        return outcome == PaymentOutcome.SETTLED;
    }
}
