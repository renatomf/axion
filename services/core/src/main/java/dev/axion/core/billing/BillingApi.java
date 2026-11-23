package dev.axion.core.billing;

import java.util.Optional;

/**
 * The only way other modules use billing (billing design, section 12.1). Calls act for the tenant
 * bound in {@code TenantContext} and join the caller's transaction.
 */
public interface BillingApi {

    /** The most charges one page of {@link #charges} holds. */
    int MAX_PAGE_SIZE = 100;

    /**
     * Creates an active charge with its transaction ID and its BR Code, and opens the tenant's
     * ledger account if it is not open yet, so the account exists before any payment can arrive
     * for it (section 4.3). Risk decides first: a charge it denies is {@link
     * CreateChargeResult.LimitExceeded} and writes nothing.
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    CreateChargeResult createCharge(ChargeRequest request);

    /** The most payments a {@link ChargeDetail} holds; a charge paid more often is an anomaly. */
    int MAX_DETAIL_PAYMENTS = 100;

    /**
     * The tenant's charge, with its first {@value #MAX_DETAIL_PAYMENTS} payments and its refunds, read from one snapshot; empty
     * for an unknown ID and for another tenant's charge alike (section 12.1).
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<ChargeDetail> charge(ChargeId charge);

    /**
     * A page of the tenant's charges that match the filter, newest first. Start from {@link
     * ChargeCursor#first()}, then each page's {@link ChargePage#next()}.
     *
     * @param limit 1 to {@value #MAX_PAGE_SIZE}
     * @throws IllegalArgumentException if the limit is out of range
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    ChargePage charges(ChargeFilter filter, ChargeCursor cursor, int limit);

    /**
     * Refunds one payment of the tenant's charge, in full or in part, by a ledger entry that
     * reverses the payment's (billing design, section 10). Refunds of one charge are made one at a
     * time, so two cannot both take what remains of a payment. A refused refund writes nothing.
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    RefundResult refund(ChargeId charge, RefundRequest request);
}
