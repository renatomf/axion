package dev.axion.core.webhooks;

/** Whether an endpoint receives events (events and webhooks design, section 6.1). */
public enum EndpointStatus {
    ENABLED,
    /** Receives nothing, for its {@link DisabledReason}. */
    DISABLED
}
