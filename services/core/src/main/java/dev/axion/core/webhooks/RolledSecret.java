package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.SigningSecret;
import java.time.Instant;

/**
 * An endpoint's new signing secret, returned once and never again, and the end of the secret it
 * replaces. A class, not a record, so {@code toString} masks the secret.
 */
public final class RolledSecret {

    private final EndpointView endpoint;
    private final SigningSecret secret;
    private final Instant previousSecretExpiresAt;

    public RolledSecret(EndpointView endpoint, SigningSecret secret, Instant previousSecretExpiresAt) {
        this.endpoint = endpoint;
        this.secret = secret;
        this.previousSecretExpiresAt = previousSecretExpiresAt;
    }

    public EndpointView endpoint() {
        return endpoint;
    }

    /** The secret as the tenant configures it: {@code whsec_} and its base64. */
    public String secret() {
        return secret.text();
    }

    /** Until then, requests carry a signature with the previous secret beside the new one's. */
    public Instant previousSecretExpiresAt() {
        return previousSecretExpiresAt;
    }

    @Override
    public String toString() {
        return "RolledSecret[id=" + endpoint.id().publicId() + ", secret=" + secret + ", previousSecretExpiresAt="
                + previousSecretExpiresAt + "]";
    }
}
