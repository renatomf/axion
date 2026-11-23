package dev.finaxis.core.billing;

import java.util.Optional;

/**
 * The only way other modules use billing (billing design, section 12.1). Calls act for the tenant
 * bound in {@code TenantContext} and join the caller's transaction.
 */
public interface BillingApi {

    /**
     * Creates an active charge with its transaction ID and its BR Code, and opens the tenant's
     * ledger account if it is not open yet, so the account exists before any payment can arrive
     * for it (section 4.3).
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    CreateChargeResult createCharge(ChargeRequest request);

    /**
     * The tenant's charge; empty for an unknown ID and for another tenant's charge alike (section
     * 12.1).
     *
     * @throws IllegalStateException if no tenant is bound, a bug in the caller
     */
    Optional<ChargeView> charge(ChargeId charge);
}
