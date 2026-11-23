package dev.axion.simulator.delivery;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the core receives the rails' messages (rails simulator design, section 8.2).
 *
 * @param url the core's base URL, such as {@code http://localhost:8080}
 */
@ConfigurationProperties("simulator.core")
record CoreProperties(URI url) {

    CoreProperties {
        if (!"http".equals(url.getScheme()) && !"https".equals(url.getScheme())) {
            throw new IllegalArgumentException("simulator.core.url must be an http or https URL: " + url);
        }
    }
}
