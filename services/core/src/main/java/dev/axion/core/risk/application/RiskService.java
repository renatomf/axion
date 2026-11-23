package dev.axion.core.risk.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.risk.ChargeDecision;
import dev.axion.core.risk.RiskApi;
import dev.axion.core.risk.RiskRule;
import dev.axion.core.risk.persistence.ChargeWindow;
import dev.axion.core.risk.persistence.JdbcChargeVolume;
import dev.axion.core.shared.BusinessCalendar;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

@Service
@EnableConfigurationProperties(RiskProperties.class)
public class RiskService implements RiskApi {

    public static final String DENIALS = "axion.risk.denials";

    private static final String ROLE = "axion_risk";

    private static final Currency BRL = Currency.getInstance("BRL");

    private final JdbcChargeVolume volume;
    private final ModuleRoles roles;
    private final RiskProperties properties;
    private final Clock clock;
    private final Map<RiskRule, Counter> denials = new EnumMap<>(RiskRule.class);

    // Registered at zero, so a rate is defined before the first denial by each rule.
    public RiskService(
            JdbcChargeVolume volume, ModuleRoles roles, RiskProperties properties, Clock clock, MeterRegistry meters) {
        this.volume = volume;
        this.roles = roles;
        this.properties = properties;
        this.clock = clock;
        for (RiskRule rule : RiskRule.values()) {
            denials.put(
                    rule,
                    Counter.builder(DENIALS)
                            .description("Charges the risk rules denied, by the rule that denied them")
                            .tag("rule", rule.name().toLowerCase(Locale.ROOT))
                            .register(meters));
        }
    }

    // MANDATORY: what the decision counts must commit or roll back with the charge it allowed.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ChargeDecision assessCharge(TenantId tenant, Money amount) {
        var decision = decide(tenant, amount);
        if (decision instanceof ChargeDecision.Denied(RiskRule rule)) {
            Objects.requireNonNull(denials.get(rule)).increment();
        }
        return decision;
    }

    private ChargeDecision decide(TenantId tenant, Money amount) {
        if (!amount.currency().equals(BRL) || !amount.isPositive()) {
            throw new IllegalArgumentException("A charge's amount must be positive, in BRL: " + amount);
        }
        var binding = bound(tenant);
        var limits = properties.of(binding.environment());
        if (amount.minorUnits() > limits.amount()) {
            return new ChargeDecision.Denied(RiskRule.AMOUNT);
        }
        var windows = windows(limits, clock.instant());
        return roles.call(ROLE, () -> addToWindows(tenant, windows, amount.minorUnits()))
                .<ChargeDecision>map(ChargeDecision.Denied::new)
                .orElseGet(ChargeDecision.Allowed::new);
    }

    // A charge the daily window took and the hourly one refused rolls back to the savepoint, so a
    // denial leaves every window as it was and the caller's work stands. Every charge locks the
    // windows in the same order, so two charges of one tenant may wait for each other, never deadlock.
    private Optional<RiskRule> addToWindows(TenantId tenant, List<ChargeWindow> windows, long amount) {
        var transaction = TransactionAspectSupport.currentTransactionStatus();
        var savepoint = transaction.createSavepoint();
        for (ChargeWindow window : windows) {
            if (!volume.tryAdd(tenant, window, amount)) {
                transaction.rollbackToSavepoint(savepoint);
                transaction.releaseSavepoint(savepoint);
                return Optional.of(window.rule());
            }
        }
        transaction.releaseSavepoint(savepoint);
        return Optional.empty();
    }

    // Each window bounds one quantity, so its other limit is never the one reached.
    private static List<ChargeWindow> windows(RiskProperties.Limits limits, Instant now) {
        Instant businessDate = BusinessCalendar.businessDateOf(now)
                .atStartOfDay(BusinessCalendar.ZONE)
                .toInstant();
        return List.of(
                new ChargeWindow(RiskRule.DAILY_AMOUNT, businessDate, limits.dailyAmount(), Integer.MAX_VALUE),
                new ChargeWindow(
                        RiskRule.HOURLY_COUNT,
                        now.truncatedTo(ChronoUnit.HOURS),
                        Long.MAX_VALUE,
                        limits.hourlyCount()));
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
