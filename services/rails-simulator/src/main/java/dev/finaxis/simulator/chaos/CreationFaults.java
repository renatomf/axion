package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The faults applied to a notification when its delivery is created, at most one of each (rails simulator
 * design, section 7.2).
 *
 * @param copies how many copies are sent besides the delivery, for a {@code duplicate} fault; zero otherwise
 * @param hold for a {@code reorder} fault, the rule that holds the delivery
 */
public record CreationFaults(
        List<AppliedFault> applied, int copies, @Nullable Hold hold) {

    public static final CreationFaults NONE = new CreationFaults(List.of(), 0, null);

    /**
     * A delivery held by a {@code reorder} rule.
     *
     * @param last true when it is the last the rule holds, so the rule releases them all with it
     */
    public record Hold(UUID rule, boolean last) {}

    public CreationFaults {
        applied = List.copyOf(applied);
    }

    /** How long the first attempt is postponed, for a {@code delay} fault. */
    public Optional<Duration> delay() {
        return applied.stream()
                .filter(fault -> fault.fault() == Fault.DELAY)
                .findFirst()
                .map(AppliedFault::waited);
    }
}
