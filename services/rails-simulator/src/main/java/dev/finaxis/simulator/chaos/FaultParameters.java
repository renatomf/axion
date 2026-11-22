package dev.finaxis.simulator.chaos;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What a rule's fault takes, each parameter for one fault only (rails simulator design, section 7.2).
 *
 * @param min for {@code latency}, the shortest wait before an attempt is sent
 * @param max for {@code latency}, the longest wait
 * @param duration for {@code delay}, how long the first attempt is postponed
 * @param copies for {@code duplicate}, how many copies are sent besides the delivery
 */
public record FaultParameters(
        @Nullable Duration min,
        @Nullable Duration max,
        @Nullable Duration duration,
        @Nullable Integer copies) {

    /** For the faults that take none. */
    public static final FaultParameters NONE = new FaultParameters(null, null, null, null);

    public static FaultParameters latency(Duration min, Duration max) {
        return new FaultParameters(min, max, null, null);
    }

    public static FaultParameters delay(Duration duration) {
        return new FaultParameters(null, null, duration, null);
    }

    public static FaultParameters duplicate(int copies) {
        return new FaultParameters(null, null, null, copies);
    }
}
