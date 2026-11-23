package dev.axion.core.billing.application;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.PixKey;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * What a BR Code names besides the tenant: the Pix key of Finaxis's account in each environment and
 * the platform's city (billing design, section 5.3).
 */
@ConfigurationProperties("finaxis.billing")
record BillingProperties(Map<Environment, String> pixKeys, String merchantCity) {

    // A random key: the other types are personal data, and nothing resolves it, since the DICT is
    // not simulated. Checked here, so a wrong key stops the start instead of the first charge.
    BillingProperties {
        pixKeys = Map.copyOf(pixKeys);
        for (Environment environment : Environment.values()) {
            String key = pixKeys.get(environment);
            Assert.hasText(
                    key, "finaxis.billing.pix-keys." + environment.name().toLowerCase(Locale.ROOT) + " must be set");
            new PixKey(PixKey.Type.RANDOM, key);
        }
        Assert.hasText(merchantCity, "finaxis.billing.merchant-city must be set");
    }

    PixKey pixKey(Environment environment) {
        return new PixKey(PixKey.Type.RANDOM, Objects.requireNonNull(pixKeys.get(environment)));
    }
}
