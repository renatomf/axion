package dev.axion.core.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.risk.application.RiskProperties.Limits;
import dev.axion.core.shared.Environment;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RiskPropertiesTest {

    private static final Limits TEST = new Limits(100_000, 1_000_000, 200);
    private static final Limits LIVE = new Limits(1_000_000, 20_000_000, 1_000);

    @Test
    void holdsTheLimitsOfEachEnvironment() {
        var properties = new RiskProperties(Map.of(Environment.TEST, TEST, Environment.LIVE, LIVE));

        assertThat(properties.of(Environment.TEST)).isEqualTo(TEST);
        assertThat(properties.of(Environment.LIVE)).isEqualTo(LIVE);
    }

    @Test
    void refusesAnEnvironmentWithoutLimits() {
        assertThatThrownBy(() -> new RiskProperties(Map.of(Environment.TEST, TEST)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("axion.risk.limits.live must be set");
    }

    @Test
    void refusesALimitThatIsNotPositive() {
        assertThatThrownBy(() -> new Limits(0, 1_000_000, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");
        assertThatThrownBy(() -> new Limits(100_000, -1, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("daily-amount must be positive");
        assertThatThrownBy(() -> new Limits(100_000, 1_000_000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("hourly-count must be positive");
    }
}
