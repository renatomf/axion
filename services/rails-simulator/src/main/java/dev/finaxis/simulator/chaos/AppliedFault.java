package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A fault a rule applied to a delivery, as recorded on it (rails simulator design, section 7.1).
 *
 * @param rule the rule that applied it
 * @param attempt the number of the attempt, from 1; none for a fault applied when the delivery was created
 * @param waited for {@code latency}, how long the attempt waited before it was sent; for {@code delay}, how
 *     long the first attempt was postponed
 */
public record AppliedFault(
        UUID rule,
        Fault fault,
        @Nullable Integer attempt,
        @Nullable Duration waited) {}
