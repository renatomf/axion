package dev.finaxis.simulator.chaos;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a rule does to each attempt it matches, or to each notification when its delivery is created (rails
 * simulator design, section 7.2).
 */
public enum Fault {
    /** The attempt waits a random time within the rule's range before it is sent. */
    LATENCY("latency", false),
    /** The attempt is not sent, and the delivery is retried. */
    FAIL("fail", false),
    /** The attempt is sent, the core's answer discarded, and the delivery is retried. */
    LOST_RESPONSE("lost_response", false),
    /** The first attempt is postponed by the rule's duration. */
    DELAY("delay", true);

    private final String tag;
    private final boolean atCreation;

    Fault(String tag, boolean atCreation) {
        this.tag = tag;
        this.atCreation = atCreation;
    }

    /** True when the fault applies once, as the delivery is created; false when it applies at each attempt. */
    public boolean atCreation() {
        return atCreation;
    }

    /** The name in the operator API and in the faults recorded on a delivery. */
    public String tag() {
        return tag;
    }

    public static Optional<Fault> ofTag(String tag) {
        return Arrays.stream(values()).filter(fault -> fault.tag.equals(tag)).findFirst();
    }
}
