package dev.finaxis.simulator.chaos;

import java.util.Arrays;
import java.util.Optional;

/** What a rule does to each attempt it matches (rails simulator design, section 7.2). */
public enum Fault {
    /** The attempt waits a random time within the rule's range before it is sent. */
    LATENCY("latency"),
    /** The attempt is not sent, and the delivery is retried. */
    FAIL("fail"),
    /** The attempt is sent, the core's answer discarded, and the delivery is retried. */
    LOST_RESPONSE("lost_response");

    private final String tag;

    Fault(String tag) {
        this.tag = tag;
    }

    /** The name in the operator API and in the faults recorded on a delivery. */
    public String tag() {
        return tag;
    }

    static Optional<Fault> ofTag(String tag) {
        return Arrays.stream(values()).filter(fault -> fault.tag.equals(tag)).findFirst();
    }
}
