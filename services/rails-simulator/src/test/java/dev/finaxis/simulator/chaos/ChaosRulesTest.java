package dev.finaxis.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChaosRulesTest {

    private static final String END_TO_END_ID = "E99990011202611211430aB3dE5gH7jK";
    private static final Instant NOW = Instant.parse("2026-11-21T14:30:12.345678Z");

    private final ChaosRules rules = new ChaosRules(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void putsARuleInForceForItsTimes() {
        FaultRule rule = rules.add(Fault.FAIL, null, null, new Match(null, "chaos1"), 3);

        assertThat(rule.times()).isEqualTo(3);
        assertThat(rule.remaining()).isEqualTo(3);
        assertThat(rule.createdAt()).isEqualTo(Instant.parse("2026-11-21T14:30:12.345Z"));
        assertThat(rules.list()).containsExactly(rule);
    }

    @Test
    void listsTheRulesOldestFirst() {
        FaultRule first = rules.add(Fault.FAIL, null, null, Match.ANY, 1);
        FaultRule second = rules.add(Fault.LOST_RESPONSE, null, null, Match.ANY, 1);

        assertThat(rules.list()).containsExactly(first, second);
    }

    @Test
    void removesOneRuleOrEvery() {
        FaultRule first = rules.add(Fault.FAIL, null, null, Match.ANY, 1);
        FaultRule second = rules.add(Fault.FAIL, null, null, Match.ANY, 1);

        assertThat(rules.remove(first.id())).isTrue();
        assertThat(rules.remove(first.id())).isFalse();
        assertThat(rules.remove(UUID.randomUUID())).isFalse();
        assertThat(rules.list()).containsExactly(second);
        rules.clear();
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void keepsARangeForLatencyOnly() {
        assertThatIllegalArgumentException().isThrownBy(() -> rules.add(Fault.LATENCY, null, null, Match.ANY, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules.add(Fault.FAIL, Duration.ZERO, Duration.ZERO, Match.ANY, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules.add(Fault.LATENCY, Duration.ofSeconds(2), Duration.ofSeconds(1), Match.ANY, 1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rules.add(Fault.LATENCY, Duration.ZERO, Duration.ofSeconds(21), Match.ANY, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> rules.add(Fault.FAIL, null, null, Match.ANY, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> rules.add(Fault.FAIL, null, null, Match.ANY, 1001));
        assertThat(rules.list()).isEmpty();
    }

    @Test
    void appliesARuleToExactlyItsNextMatchingAttempts() {
        FaultRule rule = rules.add(Fault.FAIL, null, null, new Match(null, "chaos4"), 2);

        assertThat(rules.atAttempt(END_TO_END_ID, "other", 1).applied()).isEmpty();
        assertThat(rules.atAttempt(END_TO_END_ID, "chaos4", 1).applied())
                .containsExactly(new AppliedFault(rule.id(), Fault.FAIL, 1, null));
        assertThat(rules.list())
                .singleElement()
                .extracting(FaultRule::remaining)
                .isEqualTo(1);
        assertThat(rules.atAttempt(END_TO_END_ID, "chaos4", 2).fails()).isTrue();
        assertThat(rules.list()).isEmpty();
        assertThat(rules.atAttempt(END_TO_END_ID, "chaos4", 3)).isEqualTo(AttemptFaults.NONE);
    }

    @Test
    void appliesTheOldestRuleOfEachFault() {
        FaultRule first = rules.add(Fault.FAIL, null, null, Match.ANY, 1);
        FaultRule second = rules.add(Fault.FAIL, null, null, Match.ANY, 1);
        FaultRule latency = rules.add(Fault.LATENCY, Duration.ZERO, Duration.ZERO, Match.ANY, 1);

        assertThat(rules.atAttempt(END_TO_END_ID, "chaos5", 1).applied())
                .extracting(AppliedFault::rule)
                .containsExactly(first.id(), latency.id());
        assertThat(rules.list()).containsExactly(second);
    }

    @Test
    void neverFailsAndLosesTheResponseOfOneAttempt() {
        FaultRule lost = rules.add(Fault.LOST_RESPONSE, null, null, Match.ANY, 1);
        FaultRule fail = rules.add(Fault.FAIL, null, null, Match.ANY, 1);

        AttemptFaults first = rules.atAttempt(END_TO_END_ID, "chaos6", 1);
        AttemptFaults second = rules.atAttempt(END_TO_END_ID, "chaos6", 2);

        assertThat(first.applied()).extracting(AppliedFault::rule).containsExactly(lost.id());
        assertThat(first.fails()).isFalse();
        assertThat(second.applied()).extracting(AppliedFault::rule).containsExactly(fail.id());
    }

    @Test
    void drawsALatencyWithinTheRange() {
        Duration min = Duration.ofMillis(100);
        Duration max = Duration.ofMillis(105);
        rules.add(Fault.LATENCY, min, max, Match.ANY, FaultRule.MAX_TIMES);

        for (int attempt = 1; attempt <= 200; attempt++) {
            assertThat(rules.atAttempt(END_TO_END_ID, "chaos7", attempt).latency())
                    .hasValueSatisfying(waited -> assertThat(waited).isBetween(min, max));
        }
    }

    @Test
    void matchesByPaymentByTransactionOrEverything() {
        String endToEndId = END_TO_END_ID;

        assertThat(Match.ANY.matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(endToEndId, null).matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(null, "chaos2").matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(endToEndId, "chaos2").matches(endToEndId, "chaos3"))
                .isFalse();
        assertThat(new Match(null, "chaos3").matches(endToEndId, "chaos2")).isFalse();
    }
}
