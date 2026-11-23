package dev.finaxis.core.risk;

import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantId;

/**
 * Decides, before a charge exists, whether its tenant may create it (billing design, section 6). The
 * rules limit what an abused or broken integration can produce, not what a tenant may sell.
 */
public interface RiskApi {

    /**
     * Allows the charge and counts it in the tenant's windows, or denies it by the first rule it
     * would break, in the order of {@link RiskRule}, and counts nothing. The call joins the caller's
     * transaction, so a charge rolled back after it was allowed gives its share of the windows back.
     *
     * @param tenant the tenant bound to the transaction; the limits are its environment's
     * @param amount positive, in BRL
     * @throws org.springframework.transaction.IllegalTransactionStateException with no transaction
     * @throws IllegalStateException with no tenant or another tenant bound
     * @throws IllegalArgumentException if the amount is not a positive amount in BRL
     */
    ChargeDecision assessCharge(TenantId tenant, Money amount);
}
