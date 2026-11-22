package dev.finaxis.simulator.delivery;

import java.util.UUID;

/** What came of asking to send a delivery again (rails simulator design, section 7.4). */
sealed interface Redelivery {

    /** A delivery of kind {@code redelivery}, due now. */
    record Created(UUID id, String endToEndId) implements Redelivery {}

    record NotFound() implements Redelivery {}

    /** The delivery is still owed to the core, or the core accepted it. */
    record NotRedeliverable(DeliveryStatus status) implements Redelivery {}
}
