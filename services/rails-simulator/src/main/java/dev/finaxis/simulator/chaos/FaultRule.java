package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * A fault rule as it stands: what it does, to which deliveries, and how many more attempts it applies to
 * (rails simulator design, section 7.1).
 *
 * @param times how many attempts the rule was added for
 * @param remaining how many more attempts it applies to before it is retired
 */
public record FaultRule(
        UUID id, Fault fault, FaultParameters parameters, Match match, int times, int remaining, Instant createdAt) {

    /** The longest {@code latency}: under the dispatcher's 30-second lease, so a waiting delivery is never claimed twice. */
    public static final Duration MAX_LATENCY = Duration.ofSeconds(20);

    public static final int MAX_TIMES = 1000;

    public FaultRule {
        if (times < 1 || times > MAX_TIMES || remaining < 1 || remaining > times) {
            throw new IllegalArgumentException("A rule applies to 1 to " + MAX_TIMES + " attempts");
        }
        Duration min = parameters.min();
        Duration max = parameters.max();
        boolean latency = fault == Fault.LATENCY;
        if (latency != (min != null) || latency != (max != null)) {
            throw new IllegalArgumentException("Only latency, and always latency, has a range");
        }
        if (min != null
                && max != null
                && (min.isNegative() || min.compareTo(max) > 0 || max.compareTo(MAX_LATENCY) > 0)) {
            throw new IllegalArgumentException("A latency range runs from zero to " + MAX_LATENCY);
        }
    }

    /** The rule after one more application; at zero remaining, it is retired instead. */
    FaultRule spent() {
        return new FaultRule(id, fault, parameters, match, times, remaining - 1, createdAt);
    }
}
