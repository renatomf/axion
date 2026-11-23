package dev.axion.core.billing.web;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import dev.axion.core.billing.ChargeDetail;
import java.util.List;

/**
 * A charge's fields, as {@link ChargeResponse} writes them, followed by its payments and its refunds,
 * each refund as its creation returned it.
 */
record ChargeDetailResponse(
        @JsonUnwrapped ChargeResponse charge, List<PaymentResponse> payments, List<RefundResponse> refunds) {

    static ChargeDetailResponse of(ChargeDetail detail) {
        return new ChargeDetailResponse(
                ChargeResponse.of(detail.charge()),
                detail.payments().stream().map(PaymentResponse::of).toList(),
                detail.refunds().stream().map(RefundResponse::of).toList());
    }
}
