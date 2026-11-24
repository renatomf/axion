package dev.axion.core.notifications.domain;

import dev.axion.core.events.PublicEvent;
import dev.axion.core.shared.EventId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * A tenant's latest public events, kept so a stream that reconnects receives what it missed (events
 * and webhooks design, section 10): the last {@value #CAPACITY}, and none kept longer than {@link
 * #WINDOW}. An event already kept is not kept again, so a listener handed it twice sends it once.
 *
 * <p>Not thread-safe: its owner guards it.
 */
public final class RecentEvents {

    public static final int CAPACITY = 200;

    public static final Duration WINDOW = Duration.ofMinutes(5);

    private final Deque<Kept> kept = new ArrayDeque<>();

    /** Keeps the event, received now; false if it is already kept. */
    public boolean add(PublicEvent event, Instant now) {
        prune(now);
        if (kept.stream().anyMatch(entry -> entry.event().id().equals(event.id()))) {
            return false;
        }
        if (kept.size() == CAPACITY) {
            kept.removeFirst();
        }
        kept.addLast(new Kept(event, now));
        return true;
    }

    /**
     * The events kept after the given one, oldest first; empty if it is no longer kept, so what came
     * between is unknown.
     */
    public Optional<List<PublicEvent>> after(EventId last, Instant now) {
        prune(now);
        List<PublicEvent> after = new ArrayList<>();
        boolean found = false;
        for (Kept entry : kept) {
            if (found) {
                after.add(entry.event());
            } else if (entry.event().id().equals(last)) {
                found = true;
            }
        }
        return found ? Optional.of(List.copyOf(after)) : Optional.empty();
    }

    private void prune(Instant now) {
        Instant oldest = now.minus(WINDOW);
        while (!kept.isEmpty() && kept.getFirst().receivedAt().isBefore(oldest)) {
            kept.removeFirst();
        }
    }

    private record Kept(PublicEvent event, Instant receivedAt) {}
}
