package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** The faults applied to one attempt to send a delivery, at most one of each (rails simulator design, section 7.2). */
public record AttemptFaults(List<AppliedFault> applied) {

    public static final AttemptFaults NONE = new AttemptFaults(List.of());

    public AttemptFaults {
        applied = List.copyOf(applied);
    }

    /** How long to wait before the attempt is sent, for a {@code latency} fault. */
    public Optional<Duration> latency() {
        return applied.stream()
                .filter(fault -> fault.fault() == Fault.LATENCY)
                .findFirst()
                .map(AppliedFault::waited);
    }

    /** True for a {@code fail} fault: the attempt is not sent. */
    public boolean fails() {
        return has(Fault.FAIL);
    }

    /** True for a {@code lost_response} fault: the attempt is sent and the core's answer discarded. */
    public boolean losesResponse() {
        return has(Fault.LOST_RESPONSE);
    }

    private boolean has(Fault fault) {
        return applied.stream().anyMatch(applied -> applied.fault() == fault);
    }
}
