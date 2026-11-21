package dev.finaxis.simulator.chaos;

import java.time.Duration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A fault a rule applied to one attempt, as recorded on its delivery (rails simulator design, section 7.1).
 *
 * @param rule the rule that applied it
 * @param attempt the number of the attempt, from 1
 * @param waited for {@code latency}, how long the attempt waited before it was sent
 */
public record AppliedFault(
        UUID rule, Fault fault, int attempt, @Nullable Duration waited) {}
