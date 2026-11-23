package dev.axion.core.billing;

import java.util.List;

/**
 * A charge with the payments it received and the refunds made of them, each oldest first: at most
 * {@link BillingApi#MAX_DETAIL_PAYMENTS} payments, and every refund (billing design, section 12.2).
 */
public record ChargeDetail(ChargeView charge, List<PaymentView> payments, List<RefundView> refunds) {

    public ChargeDetail {
        payments = List.copyOf(payments);
        refunds = List.copyOf(refunds);
    }
}
