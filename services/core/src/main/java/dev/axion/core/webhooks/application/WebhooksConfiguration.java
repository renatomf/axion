package dev.axion.core.webhooks.application;

import dev.axion.core.webhooks.domain.HostResolver;
import dev.axion.core.webhooks.domain.LocalReceivers;
import dev.axion.core.webhooks.domain.PublicAddressPolicy;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
import dev.axion.core.webhooks.http.CheckedDnsResolver;
import dev.axion.core.webhooks.http.WebhookClient;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
class WebhooksConfiguration {

    // A bean, so the tests resolve their names without the network.
    @Bean
    HostResolver hostResolver() {
        return HostResolver.SYSTEM;
    }

    @Bean
    LocalReceivers localReceivers(Environment environment) {
        return localReceiversOf(environment);
    }

    @Bean
    WebhookUrlValidator webhookUrlValidator(HostResolver resolver, LocalReceivers local) {
        return new WebhookUrlValidator(resolver, local);
    }

    // Connects only to public addresses, and to localhost for a developer's local receivers; trusts
    // the JVM's certificate authorities. Closed with the context, as an AutoCloseable bean.
    @Bean
    WebhookClient webhookClient(HostResolver resolver, LocalReceivers local) throws NoSuchAlgorithmException {
        return new WebhookClient(
                new CheckedDnsResolver(resolver, PublicAddressPolicy::isPublic, local),
                SSLContext.getDefault(),
                WebhookClient.Timeouts.DEFAULT,
                DeliveryQueue.IN_FLIGHT);
    }

    // Read in the local profile only: in any other, no setting lets the core reach its own host.
    static LocalReceivers localReceiversOf(Environment environment) {
        if (!environment.matchesProfiles("local")) {
            return LocalReceivers.NONE;
        }
        List<Integer> ports = Binder.get(environment)
                .bind("axion.webhooks.allowed-local-ports", Bindable.listOf(Integer.class))
                .orElse(List.of());
        return new LocalReceivers(Set.copyOf(ports));
    }
}
