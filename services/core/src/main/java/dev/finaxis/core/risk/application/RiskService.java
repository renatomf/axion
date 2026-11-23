package dev.finaxis.core.risk.application;

import dev.finaxis.core.risk.ChargeDecision;
import dev.finaxis.core.risk.RiskApi;
import dev.finaxis.core.risk.RiskRule;
import dev.finaxis.core.shared.Money;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import java.util.Currency;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(RiskProperties.class)
public class RiskService implements RiskApi {

    private static final Currency BRL = Currency.getInstance("BRL");

    private final RiskProperties properties;

    public RiskService(RiskProperties properties) {
        this.properties = properties;
    }

    // MANDATORY: what the decision counts must commit or roll back with the charge it allowed.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ChargeDecision assessCharge(TenantId tenant, Money amount) {
        if (!amount.currency().equals(BRL) || !amount.isPositive()) {
            throw new IllegalArgumentException("A charge's amount must be positive, in BRL: " + amount);
        }
        var binding = bound(tenant);
        var limits = properties.of(binding.environment());
        if (amount.minorUnits() > limits.amount()) {
            return new ChargeDecision.Denied(RiskRule.AMOUNT);
        }
        return new ChargeDecision.Allowed();
    }

    // The limits are those of the bound tenant's environment, so the two must be the same tenant.
    private static TenantContext.Binding bound(TenantId tenant) {
        var binding = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("Assessing a charge needs a tenant bound"));
        if (!binding.tenant().equals(tenant)) {
            throw new IllegalStateException("Assessing a charge of a tenant other than the one bound");
        }
        return binding;
    }
}
