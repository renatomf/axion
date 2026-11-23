package dev.axion.core.billing.application;

import static dev.axion.core.billing.BillingApi.MAX_DETAIL_PAYMENTS;

import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.persistence.JdbcCharges;
import dev.axion.core.billing.persistence.JdbcPayments;
import dev.axion.core.billing.persistence.JdbcRefunds;
import dev.axion.core.shared.TenantContext;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads a charge with its payments and its refunds, as {@link dev.axion.core.billing.BillingApi#charge}
 * returns it and billing's events carry it. Runs in the caller's transaction, as billing's role.
 */
@Component
public class ChargeDetails {

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;

    public ChargeDetails(JdbcCharges charges, JdbcPayments payments, JdbcRefunds refunds) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
    }

    public Optional<ChargeDetail> find(TenantContext.Binding tenant, ChargeId charge) {
        return charges.find(tenant, charge)
                .map(found -> new ChargeDetail(
                        found, payments.views(found.id(), MAX_DETAIL_PAYMENTS), refunds.views(found.id())));
    }
}
