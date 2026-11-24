package dev.axion.core.webhooks;

/** Where a delivery stands (events and webhooks design, sections 8.2 and 8.5). */
public enum DeliveryStatus {
    /** Waiting for its next attempt, or in the middle of one. */
    PENDING,
    /** The receiver took the event. */
    SUCCEEDED,
    /** Its attempts ran out, or the receiver answered that the URL is gone. */
    DEAD,
    /** Its endpoint stopped receiving, deleted or disabled, before it ended. */
    CANCELED
}
