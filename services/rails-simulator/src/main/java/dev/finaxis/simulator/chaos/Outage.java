package dev.finaxis.simulator.chaos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The outage switch: while it is on, the rails are down, so no payment is settled and no delivery is
 * attempted (rails simulator design, section 7.3). Held in memory, as the rules are: a restart ends it.
 */
@Component
public class Outage {

    /** The longest outage with a duration; a longer one lasts until it is ended. */
    public static final Duration MAX_DURATION = Duration.ofHours(1);

    /**
     * An outage in force.
     *
     * @param endsAt when it ends on its own; none when it lasts until it is ended
     */
    public record State(Instant startedAt, @Nullable Instant endsAt) {}

    private final Clock clock;
    private @Nullable State state;

    Outage(Clock clock) {
        this.clock = clock;
    }

    /** Takes the rails down from now on, replacing any outage in force. */
    public synchronized State start(@Nullable Duration duration) {
        if (duration != null && (!duration.isPositive() || duration.compareTo(MAX_DURATION) > 0)) {
            throw new IllegalArgumentException("An outage lasts more than zero and at most " + MAX_DURATION);
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        State started = new State(now, duration == null ? null : now.plus(duration));
        state = started;
        return started;
    }

    public synchronized void end() {
        state = null;
    }

    /** The outage in force now, if any; one whose time is up has ended. */
    public synchronized Optional<State> current() {
        State current = state;
        if (current != null) {
            Instant endsAt = current.endsAt();
            if (endsAt != null && !clock.instant().isBefore(endsAt)) {
                state = null;
                return Optional.empty();
            }
        }
        return Optional.ofNullable(current);
    }
}
