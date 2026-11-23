package dev.finaxis.core.billing.web;

import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.RefundRequest;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.PublicId;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

// Checked here, so a malformed field is a 422 naming it. Whether the payment is the charge's and
// the amount remains of it are billing's to decide.
record CreateRefundRequest(
        @Min(1) @Nullable Long amount,

        @Pattern(regexp = "pmt_[0-7][0-9a-hjkmnp-tv-z]{25}", message = "must be the ID of a payment") @Nullable
        String payment) {

    RefundRequest toRefundRequest(String correlationId) {
        return new RefundRequest(
                Optional.ofNullable(payment)
                        .map(id -> new PaymentId(
                                PublicId.parse(PaymentId.PREFIX, id).uuid())),
                Optional.ofNullable(amount).map(minorUnits -> Money.ofMinor(minorUnits, Charge.CURRENCY)),
                correlationId);
    }
}
