package dev.axion.core.webhooks;

import dev.axion.core.webhooks.domain.SigningSecret;

/**
 * An endpoint just registered, with its signing secret, returned once and never again. A class, not
 * a record, so {@code toString} masks the secret.
 */
public final class CreatedEndpoint {

    private final EndpointView endpoint;
    private final SigningSecret secret;

    public CreatedEndpoint(EndpointView endpoint, SigningSecret secret) {
        this.endpoint = endpoint;
        this.secret = secret;
    }

    public EndpointView endpoint() {
        return endpoint;
    }

    /** The secret as the tenant configures it: {@code whsec_} and its base64. */
    public String secret() {
        return secret.text();
    }

    @Override
    public String toString() {
        return "CreatedEndpoint[id=" + endpoint.id().publicId() + ", secret=" + secret + "]";
    }
}
