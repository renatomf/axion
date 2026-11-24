package dev.axion.core.events;

/**
 * The OpenAPI document that describes a public type (events and webhooks design, section 5.2): the
 * public reference for the types webhooks send, the internal one for those only the console's live
 * stream receives.
 */
public enum EventContract {
    V1("v1.yaml"),
    INTERNAL("internal.yaml");

    private final String document;

    EventContract(String document) {
        this.document = document;
    }

    String document() {
        return document;
    }
}
