package dev.finaxis.core.billing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.billing.PaymentView;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

record PaymentResponse(
        String id,
        @JsonProperty("end_to_end_id") String endToEndId,
        long amount,
        @JsonProperty("amount_refunded") long amountRefunded,
        @JsonProperty("accepted_at") Instant acceptedAt,
        String outcome,
        @JsonProperty("review_reason") @Nullable String reviewReason) {

    static PaymentResponse of(PaymentView payment) {
        return new PaymentResponse(
                payment.id().publicId().toString(),
                payment.endToEndId(),
                payment.amount().minorUnits(),
                payment.amountRefunded().minorUnits(),
                payment.acceptedAt(),
                payment.outcome().name().toLowerCase(Locale.ROOT),
                payment.reviewReason()
                        .map(reason -> reason.name().toLowerCase(Locale.ROOT))
                        .orElse(null));
    }
}
