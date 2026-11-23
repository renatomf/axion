package dev.axion.core.webhooks.application;

import dev.axion.core.webhooks.domain.SigningSecret;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.Assert;

/**
 * The key that encrypts the endpoints' signing secrets (events and webhooks design, section 6.2):
 * 32 bytes in base64, for AES-256-GCM. It comes from the environment and never reaches the
 * database, so a copy of the database holds no secret it can read.
 */
@ConfigurationProperties("axion.webhooks")
record WebhooksProperties(String secretsKey) {

    // Checked here, so a missing or wrong key stops the start instead of the first registration.
    WebhooksProperties {
        Assert.hasText(secretsKey, "axion.webhooks.secrets-key must be set");
        byte[] key;
        try {
            key = Base64.getDecoder().decode(secretsKey);
        } catch (IllegalArgumentException notBase64) {
            throw new IllegalArgumentException("axion.webhooks.secrets-key must be base64", notBase64);
        }
        Assert.isTrue(
                key.length == SigningSecret.LENGTH,
                "axion.webhooks.secrets-key must be " + SigningSecret.LENGTH + " bytes, not " + key.length);
    }

    byte[] secretsKeyBytes() {
        return Base64.getDecoder().decode(secretsKey);
    }

    // The generated toString would print the key.
    @Override
    public String toString() {
        return "WebhooksProperties[secretsKey=…]";
    }
}
