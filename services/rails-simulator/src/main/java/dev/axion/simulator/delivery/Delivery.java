package dev.axion.simulator.delivery;

import dev.axion.simulator.chaos.AppliedFault;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A delivery of a message to the core and every attempt made, for following a payment to the core (rails
 * simulator design, section 4.4).
 *
 * @param kind {@code original}, {@code duplicate} or {@code redelivery}
 * @param nextAttemptAt when it is sent next, while it is pending
 * @param faults the chaos faults applied to it, in the order applied
 */
public record Delivery(
        UUID id,
        String kind,
        DeliveryStatus status,
        Instant createdAt,
        @Nullable Instant nextAttemptAt,
        List<Attempt> attempts,
        List<AppliedFault> faults) {

    public Delivery {
        attempts = List.copyOf(attempts);
        faults = List.copyOf(faults);
    }

    /**
     * One attempt and what came of it.
     *
     * @param responseStatus the core's status code, if it answered
     * @param error {@code timeout} or {@code connection_failed}, if it did not
     * @param outcome {@code delivered}, {@code retried}, {@code refused} or {@code failed}
     */
    public record Attempt(
            int number,
            Instant attemptedAt,
            @Nullable Integer responseStatus,
            @Nullable String error,
            String outcome) {}
}
