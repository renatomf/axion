package dev.finaxis.core.billing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.billing.RefundView;
import java.time.Instant;
import java.util.Locale;

record RefundResponse(
        String id,
        String object,
        String charge,
        String payment,
        long amount,
        String status,
        @JsonProperty("created_at") Instant createdAt) {

    static RefundResponse of(RefundView refund) {
        return new RefundResponse(
                refund.id().publicId().toString(),
                "refund",
                refund.charge().publicId().toString(),
                refund.payment().publicId().toString(),
                refund.amount().minorUnits(),
                refund.status().name().toLowerCase(Locale.ROOT),
                refund.createdAt());
    }
}
