package dev.finaxis.simulator.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The fault rules in force, held in memory, so that no fault outlives the process that set it (rails
 * simulator design, section 7.1). Every method takes the one lock: rules are few, and an attempt must
 * see a rule's count and spend it as one step.
 */
@Component
public class ChaosRules {

    private final Clock clock;
    private final Map<UUID, FaultRule> rules = new LinkedHashMap<>();

    ChaosRules(Clock clock) {
        this.clock = clock;
    }

    /** Puts a rule in force for the next {@code times} matching attempts. */
    public synchronized FaultRule add(
            Fault fault, @Nullable Duration min, @Nullable Duration max, Match match, int times) {
        var rule = new FaultRule(
                UUID.randomUUID(),
                fault,
                min,
                max,
                match,
                times,
                times,
                clock.instant().truncatedTo(ChronoUnit.MILLIS));
        rules.put(rule.id(), rule);
        return rule;
    }

    /** The rules in force, oldest first. */
    public synchronized List<FaultRule> list() {
        return List.copyOf(rules.values());
    }

    /** Ends a rule before it is retired; false when no rule in force has this id. */
    public synchronized boolean remove(UUID id) {
        return rules.remove(id) != null;
    }

    public synchronized void clear() {
        rules.clear();
    }
}
