package dev.axion.simulator.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
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
    private final Map<UUID, Instant> completed = new HashMap<>();
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

    /** The ids of the {@code reorder} rules in force, which may still hold deliveries back. */
    public synchronized Set<UUID> holding() {
        return rules.values().stream()
                .filter(rule -> rule.fault() == Fault.REORDER)
                .map(FaultRule::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * When a {@code reorder} rule held its last delivery, which releases the others with it; empty for a
     * rule still in force, removed, or set before a restart.
     */
    public synchronized Optional<Instant> completedAt(UUID rule) {
        return Optional.ofNullable(completed.get(rule));
    }

    /** The faults to apply to a notification as its delivery is created, spent as {@link #atAttempt} spends them. */
    public synchronized CreationFaults atCreation(String endToEndId, String transactionId) {
        List<AppliedFault> applied = new ArrayList<>();
        int copies = 0;
        CreationFaults.@Nullable Hold hold = null;
        for (FaultRule rule : spend(endToEndId, transactionId, true)) {
            applied.add(new AppliedFault(
                    rule.id(), rule.fault(), null, rule.parameters().duration()));
            Integer ruleCopies = rule.parameters().copies();
            if (ruleCopies != null) {
                copies = ruleCopies;
            }
            if (rule.fault() == Fault.REORDER) {
                boolean last = rule.remaining() == 1;
                hold = new CreationFaults.Hold(rule.id(), last);
                if (last) {
                    completed.put(rule.id(), clock.instant());
                }
            }
        }
        return new CreationFaults(applied, copies, hold);
    }

    /**
     * The faults to apply to an attempt, spending one application of each rule that applies: of the
     * rules that match, the oldest of each fault, and of {@code fail} and {@code lost_response} only the
     * oldest. A rule spent to zero is retired.
     *
     * @param attempt the number of the attempt about to be made, from 1
     */
    public synchronized AttemptFaults atAttempt(String endToEndId, String transactionId, int attempt) {
        return new AttemptFaults(spend(endToEndId, transactionId, false).stream()
                .map(rule -> new AppliedFault(rule.id(), rule.fault(), attempt, latency(rule)))
                .toList());
    }

    /** Spends one application of each rule that applies, of the faults at creation or of those at each attempt. */
    private List<FaultRule> spend(String endToEndId, String transactionId, boolean atCreation) {
        List<FaultRule> applied = new ArrayList<>();
        Set<Fault> taken = EnumSet.noneOf(Fault.class);
        for (FaultRule rule : List.copyOf(rules.values())) {
            Fault fault = rule.fault();
            boolean excluded = EXCLUSIVE.contains(fault) && taken.stream().anyMatch(EXCLUSIVE::contains);
            if (fault.atCreation() != atCreation
                    || taken.contains(fault)
                    || excluded
                    || !rule.match().matches(endToEndId, transactionId)) {
                continue;
            }
            taken.add(fault);
            applied.add(rule);
            if (rule.remaining() == 1) {
                rules.remove(rule.id());
            } else {
                rules.put(rule.id(), rule.spent());
            }
        }
        return applied;
    }

    /** For {@code latency}, a wait drawn uniformly within the rule's range, both ends included, to the millisecond. */
    private @Nullable Duration latency(FaultRule rule) {
        Duration min = rule.parameters().min();
        Duration max = rule.parameters().max();
        return min == null || max == null
                ? null
                : min.plusMillis(random.nextLong(max.minus(min).toMillis() + 1));
    }
}
