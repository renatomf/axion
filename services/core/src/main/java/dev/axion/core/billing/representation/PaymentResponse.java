package dev.axion.core.billing.representation;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.billing.PaymentView;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

public record PaymentResponse(
        String id,
        @JsonProperty("end_to_end_id") String endToEndId,
        long amount,
        @JsonProperty("amount_refunded") long amountRefunded,
        @JsonProperty("accepted_at") Instant acceptedAt,
        String outcome,
        @JsonProperty("review_reason") @Nullable String reviewReason) {

    public static PaymentResponse of(PaymentView payment) {
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
