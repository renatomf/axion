package dev.axion.core.webhooks.application;

import dev.axion.core.webhooks.domain.HostResolver;
import dev.axion.core.webhooks.domain.PublicAddressPolicy;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
import dev.axion.core.webhooks.http.CheckedDnsResolver;
import dev.axion.core.webhooks.http.WebhookClient;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import javax.net.ssl.SSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WebhooksConfiguration {

    // A bean, so the tests resolve their names without the network.
    @Bean
    HostResolver hostResolver() {
        return HostResolver.SYSTEM;
    }

    @Bean
    WebhookUrlValidator webhookUrlValidator(HostResolver resolver) {
        return new WebhookUrlValidator(resolver);
    }

    // Connects only to public addresses and trusts the JVM's certificate authorities. Closed with
    // the context, as an AutoCloseable bean.
    @Bean
    WebhookClient webhookClient(HostResolver resolver, Clock clock) throws NoSuchAlgorithmException {
        return new WebhookClient(
                new CheckedDnsResolver(resolver, PublicAddressPolicy::isPublic),
                SSLContext.getDefault(),
                WebhookClient.Timeouts.DEFAULT,
                DeliveryQueue.IN_FLIGHT,
                clock);
    }
}
