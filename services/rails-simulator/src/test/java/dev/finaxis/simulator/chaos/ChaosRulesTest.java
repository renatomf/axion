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
    void matchesByPaymentByTransactionOrEverything() {
        String endToEndId = "E99990011202611211430aB3dE5gH7jK";

        assertThat(Match.ANY.matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(endToEndId, null).matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(null, "chaos2").matches(endToEndId, "chaos2")).isTrue();
        assertThat(new Match(endToEndId, "chaos2").matches(endToEndId, "chaos3"))
                .isFalse();
        assertThat(new Match(null, "chaos3").matches(endToEndId, "chaos2")).isFalse();
    }
}
