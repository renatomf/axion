package dev.finaxis.core.billing;

import dev.finaxis.core.shared.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * A payment a charge received, as other modules see it (billing design, section 12.2). Whatever its
 * outcome, its amount was credited to the tenant.
 *
 * @param endToEndId the payment's identity on the rails
 * @param amountRefunded what refunds returned of this payment
 * @param acceptedAt when the rails accepted it, which decided whether it was late
 * @param reviewReason why a flagged payment did not settle the charge; empty for the settling one
 */
public record PaymentView(
        PaymentId id,
        String endToEndId,
        Money amount,
        Money amountRefunded,
        Instant acceptedAt,
        PaymentOutcome outcome,
        Optional<ReviewReason> reviewReason) {}
