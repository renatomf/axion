package dev.finaxis.core.billing.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.finaxis.core.billing.ChargeDetail;
import java.util.List;

/** A charge's fields, as {@link ChargeResponse} writes them, followed by its payments. */
record ChargeDetailResponse(@JsonUnwrapped ChargeResponse charge, List<PaymentResponse> payments) {

    static ChargeDetailResponse of(ChargeDetail detail) {
        return new ChargeDetailResponse(
                ChargeResponse.of(detail.charge()),
                detail.payments().stream().map(PaymentResponse::of).toList());
    }
}
