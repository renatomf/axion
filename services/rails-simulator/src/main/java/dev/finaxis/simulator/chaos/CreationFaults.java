package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The faults applied to a notification when its delivery is created, at most one of each (rails simulator
 * design, section 7.2).
 */
public record CreationFaults(List<AppliedFault> applied) {

    public static final CreationFaults NONE = new CreationFaults(List.of());

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
