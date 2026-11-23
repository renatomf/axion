package dev.axion.simulator.chaos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutageTest {

    private static final Instant NOW = Instant.parse("2026-11-21T14:30:12.345678Z");

    private final MovableClock clock = new MovableClock();
    private final Outage outage = new Outage(clock);

    @Test
    void isOffUntilStarted() {
        assertThat(outage.current()).isEmpty();
    }

    @Test
    void lastsUntilEndedWithoutADuration() {
        Outage.State started = outage.start(null);

        clock.advance(Duration.ofDays(1));

        assertThat(started).isEqualTo(new Outage.State(Instant.parse("2026-11-21T14:30:12.345Z"), null));
        assertThat(outage.current()).contains(started);
        outage.end();
        assertThat(outage.current()).isEmpty();
    }

    @Test
    void endsOnItsOwnWhenItsTimeIsUp() {
        Outage.State started = outage.start(Duration.ofSeconds(30));

        clock.advance(Duration.ofSeconds(29));
        assertThat(outage.current()).contains(started);
        clock.advance(Duration.ofSeconds(1));
        assertThat(outage.current()).isEmpty();
    }

    @Test
    void replacesTheOutageInForce() {
        outage.start(Duration.ofSeconds(30));

        Outage.State replaced = outage.start(null);

        clock.advance(Duration.ofMinutes(1));
        assertThat(outage.current()).contains(replaced);
    }

    @Test
    void lastsMoreThanZeroAndAtMostAnHour() {
        assertThatIllegalArgumentException().isThrownBy(() -> outage.start(Duration.ZERO));
        assertThatIllegalArgumentException().isThrownBy(() -> outage.start(Duration.ofMillis(3_600_001)));
        assertThat(outage.current()).isEmpty();
    }

    private static final class MovableClock extends Clock {

        private Instant now = NOW;

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }
}
