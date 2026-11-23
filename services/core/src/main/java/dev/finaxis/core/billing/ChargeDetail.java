package dev.finaxis.core.billing;

import java.util.List;

/**
 * A charge with the payments it received, oldest first: at most {@link BillingApi#MAX_DETAIL_PAYMENTS}
 * of them (billing design, section 12.2).
 */
public record ChargeDetail(ChargeView charge, List<PaymentView> payments) {

    public ChargeDetail {
        payments = List.copyOf(payments);
    }
}
