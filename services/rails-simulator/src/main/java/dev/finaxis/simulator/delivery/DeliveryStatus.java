package dev.finaxis.simulator.delivery;

/** Where a delivery stands (rails simulator design, section 8.3). */
public enum DeliveryStatus {
    /** Due now or later. */
    PENDING,
    /** The core accepted it. */
    DELIVERED,
    /** The core refused it for good; kept for an operator to redeliver. */
    REFUSED,
    /** Not accepted within the retry window; kept for an operator to redeliver. */
    FAILED
}
