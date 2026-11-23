package dev.axion.core.webhooks;

/** Why an endpoint is disabled (events and webhooks design, section 6.1). */
public enum DisabledReason {
    /** Its deliveries kept failing (section 8.6). */
    FAILING,
    /** The tenant disabled it. */
    BY_TENANT
}
