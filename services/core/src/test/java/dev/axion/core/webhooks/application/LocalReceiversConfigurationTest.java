package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.webhooks.domain.LocalReceivers;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The ports of {@code axion.webhooks.allowed-local-ports}, read in the {@code local} profile only. */
class LocalReceiversConfigurationTest {

    @Test
    void readsThePortsInTheLocalProfile() {
        var environment = new MockEnvironment().withProperty("axion.webhooks.allowed-local-ports", "4000,8443");
        environment.setActiveProfiles("local");

        assertThat(WebhooksConfiguration.localReceiversOf(environment))
                .isEqualTo(new LocalReceivers(Set.of(4000, 8443)));
    }

    @Test
    void listsNoPortWhenTheLocalProfileSetsNone() {
        var environment = new MockEnvironment().withProperty("axion.webhooks.allowed-local-ports", "");
        environment.setActiveProfiles("local");

        assertThat(WebhooksConfiguration.localReceiversOf(environment)).isEqualTo(LocalReceivers.NONE);
    }

    @Test
    void ignoresThePortsInAnyOtherProfile() {
        var environment = new MockEnvironment().withProperty("axion.webhooks.allowed-local-ports", "4000");
        environment.setActiveProfiles("prod");

        assertThat(WebhooksConfiguration.localReceiversOf(environment)).isEqualTo(LocalReceivers.NONE);
    }

    @Test
    void ignoresThePortsWithoutAProfile() {
        var environment = new MockEnvironment().withProperty("axion.webhooks.allowed-local-ports", "4000");

        assertThat(WebhooksConfiguration.localReceiversOf(environment)).isEqualTo(LocalReceivers.NONE);
    }
}
