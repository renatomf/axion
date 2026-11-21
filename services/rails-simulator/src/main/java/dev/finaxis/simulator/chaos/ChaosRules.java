package dev.finaxis.simulator.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * The fault rules in force, held in memory, so that no fault outlives the process that set it (rails
 * simulator design, section 7.1). Every method takes the one lock: rules are few, and an attempt must
 * see a rule's count and spend it as one step.
 */
@Component
public class ChaosRules {

    /** A message is either never sent or sent and its answer lost; never both on one attempt. */
    private static final Set<Fault> EXCLUSIVE = EnumSet.of(Fault.FAIL, Fault.LOST_RESPONSE);

    private final Clock clock;
    private final Map<UUID, FaultRule> rules = new LinkedHashMap<>();
    private final RandomGenerator random = RandomGenerator.getDefault();

    ChaosRules(Clock clock) {
        this.clock = clock;
    }

    /** Puts a rule in force for the next {@code times} matching attempts. */
    public synchronized FaultRule add(Fault fault, FaultParameters parameters, Match match, int times) {
        var rule = new FaultRule(
                UUID.randomUUID(),
                fault,
                parameters,
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

    /**
     * The faults to apply to an attempt, spending one application of each rule that applies: of the
     * rules that match, the oldest of each fault, and of {@code fail} and {@code lost_response} only the
     * oldest. A rule spent to zero is retired.
     *
     * @param attempt the number of the attempt about to be made, from 1
     */
    public synchronized AttemptFaults atAttempt(String endToEndId, String transactionId, int attempt) {
        List<AppliedFault> applied = new ArrayList<>();
        Set<Fault> taken = EnumSet.noneOf(Fault.class);
        for (FaultRule rule : List.copyOf(rules.values())) {
            Fault fault = rule.fault();
            boolean excluded = EXCLUSIVE.contains(fault) && taken.stream().anyMatch(EXCLUSIVE::contains);
            if (taken.contains(fault) || excluded || !rule.match().matches(endToEndId, transactionId)) {
                continue;
            }
            taken.add(fault);
            applied.add(new AppliedFault(rule.id(), fault, attempt, fault == Fault.LATENCY ? waited(rule) : null));
            if (rule.remaining() == 1) {
                rules.remove(rule.id());
            } else {
                rules.put(rule.id(), rule.spent());
            }
        }
        return new AttemptFaults(applied);
    }

    /** A wait drawn uniformly within the rule's range, both ends included, to the millisecond. */
    private Duration waited(FaultRule rule) {
        Duration min = rule.parameters().min();
        Duration max = rule.parameters().max();
        if (min == null || max == null) {
            throw new IllegalStateException("A latency rule without a range");
        }
        return min.plusMillis(random.nextLong(max.minus(min).toMillis() + 1));
    }
}
