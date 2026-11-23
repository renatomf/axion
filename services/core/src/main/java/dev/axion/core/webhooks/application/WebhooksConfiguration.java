package dev.axion.core.webhooks.application;

import dev.axion.core.webhooks.domain.HostResolver;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
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
}
