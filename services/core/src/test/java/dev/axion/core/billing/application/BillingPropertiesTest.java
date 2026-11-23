package dev.axion.core.billing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.shared.Environment;
import dev.axion.core.shared.PixKey;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

    private static final String TEST_KEY = UUID.randomUUID().toString();
    private static final String LIVE_KEY = UUID.randomUUID().toString();

    @Test
    void namesARandomKeyForEachEnvironment() {
        var properties =
                new BillingProperties(Map.of(Environment.TEST, TEST_KEY, Environment.LIVE, LIVE_KEY), "SAO PAULO");

        assertThat(properties.pixKey(Environment.TEST)).isEqualTo(new PixKey(PixKey.Type.RANDOM, TEST_KEY));
        assertThat(properties.pixKey(Environment.LIVE)).isEqualTo(new PixKey(PixKey.Type.RANDOM, LIVE_KEY));
    }

    @Test
    void refusesAnEnvironmentWithoutKey() {
        assertThatThrownBy(() -> new BillingProperties(Map.of(Environment.TEST, TEST_KEY), "SAO PAULO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("axion.billing.pix-keys.live must be set");
    }

    @Test
    void refusesAKeyOfAnotherType() {
        assertThatThrownBy(() -> new BillingProperties(
                        Map.of(Environment.TEST, "fulano@example.com", Environment.LIVE, LIVE_KEY), "SAO PAULO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RANDOM");
    }

    @Test
    void refusesABlankCity() {
        assertThatThrownBy(() ->
                        new BillingProperties(Map.of(Environment.TEST, TEST_KEY, Environment.LIVE, LIVE_KEY), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("axion.billing.merchant-city must be set");
    }
}
