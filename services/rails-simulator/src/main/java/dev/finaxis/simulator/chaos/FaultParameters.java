package dev.finaxis.simulator.chaos;

import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * What a rule's fault takes, each parameter for one fault only (rails simulator design, section 7.2).
 *
 * @param min for {@code latency}, the shortest wait before an attempt is sent
 * @param max for {@code latency}, the longest wait
 */
public record FaultParameters(
        @Nullable Duration min, @Nullable Duration max) {

    /** For the faults that take none. */
    public static final FaultParameters NONE = new FaultParameters(null, null);

    public static FaultParameters latency(Duration min, Duration max) {
        return new FaultParameters(min, max);
    }
}
