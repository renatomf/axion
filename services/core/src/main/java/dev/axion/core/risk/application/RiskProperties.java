package dev.axion.core.risk.application;

import dev.axion.core.shared.Environment;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * The limits of each environment's charges (billing design, section 6). They are the same for every
 * tenant of an environment until the back office sets them per tenant.
 */
@ConfigurationProperties("finaxis.risk")
record RiskProperties(Map<Environment, Limits> limits) {

    // Checked here, so a missing or zero limit stops the start instead of denying every charge.
    RiskProperties {
        limits = Map.copyOf(limits);
        for (Environment environment : Environment.values()) {
            Assert.notNull(
                    limits.get(environment),
                    "finaxis.risk.limits." + environment.name().toLowerCase(Locale.ROOT) + " must be set");
        }
    }

    Limits of(Environment environment) {
        return Objects.requireNonNull(limits.get(environment));
    }

    /**
     * @param amount the largest charge, in centavos
     * @param dailyAmount the largest total of the charges created on one business date, in centavos
     * @param hourlyCount the most charges created in one clock hour
     */
    record Limits(long amount, long dailyAmount, int hourlyCount) {

        Limits {
            Assert.isTrue(amount > 0, "amount must be positive: " + amount);
            Assert.isTrue(dailyAmount > 0, "daily-amount must be positive: " + dailyAmount);
            Assert.isTrue(hourlyCount > 0, "hourly-count must be positive: " + hourlyCount);
        }
    }
}
