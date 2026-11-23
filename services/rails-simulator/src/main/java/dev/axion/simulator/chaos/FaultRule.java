package dev.axion.simulator.chaos;

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

    /** The longest {@code delay}: enough for a notification to arrive after its charge expired. */
    public static final Duration MAX_DELAY = Duration.ofHours(1);

    /** The most copies of one delivery: enough for any race on deduplication, too few to flood the core. */
    public static final int MAX_COPIES = 10;

    public static final int MAX_TIMES = 1000;

    public FaultRule {
        if (times < 1 || times > MAX_TIMES || remaining < 1 || remaining > times) {
            throw new IllegalArgumentException("A rule applies to 1 to " + MAX_TIMES + " attempts");
        }
        if (fault == Fault.REORDER && times < 2) {
            throw new IllegalArgumentException("A reorder holds at least 2 deliveries");
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
        Duration duration = parameters.duration();
        if ((fault == Fault.DELAY) != (duration != null)) {
            throw new IllegalArgumentException("Only delay, and always delay, has a duration");
        }
        if (duration != null && (!duration.isPositive() || duration.compareTo(MAX_DELAY) > 0)) {
            throw new IllegalArgumentException("A delay is more than zero and at most " + MAX_DELAY);
        }
        Integer copies = parameters.copies();
        if ((fault == Fault.DUPLICATE) != (copies != null)) {
            throw new IllegalArgumentException("Only duplicate, and always duplicate, has copies");
        }
        if (copies != null && (copies < 1 || copies > MAX_COPIES)) {
            throw new IllegalArgumentException("A duplicate has 1 to " + MAX_COPIES + " copies");
        }
    }

    /** The rule after one more application; at zero remaining, it is retired instead. */
    FaultRule spent() {
        return new FaultRule(id, fault, parameters, match, times, remaining - 1, createdAt);
    }
}
