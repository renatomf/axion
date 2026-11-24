package dev.axion.core.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.events.PublicEvent;
import dev.axion.core.shared.EventId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecentEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private final RecentEvents recent = new RecentEvents();

    @Test
    void givesTheEventsAfterTheLastOneReceivedOldestFirst() {
        PublicEvent first = event();
        PublicEvent second = event();
        PublicEvent third = event();
        recent.add(first, NOW);
        recent.add(second, NOW);
        recent.add(third, NOW);

        assertThat(recent.after(first.id(), NOW)).contains(List.of(second, third));
        assertThat(recent.after(third.id(), NOW)).contains(List.of());
    }

    @Test
    void knowsNothingAfterAnEventItDoesNotKeep() {
        recent.add(event(), NOW);

        assertThat(recent.after(new EventId(UUID.randomUUID()), NOW)).isEmpty();
    }

    @Test
    void keepsTheLatestEventsUpToItsCapacity() {
        List<PublicEvent> events = new ArrayList<>();
        for (int i = 0; i <= RecentEvents.CAPACITY; i++) {
            PublicEvent event = event();
            events.add(event);
            recent.add(event, NOW);
        }

        assertThat(recent.after(events.get(0).id(), NOW)).isEmpty();
        assertThat(recent.after(events.get(1).id(), NOW))
                .hasValueSatisfying(after -> assertThat(after).hasSize(RecentEvents.CAPACITY - 1));
    }

    @Test
    void forgetsAnEventOnceItsWindowHasPassed() {
        PublicEvent event = event();
        recent.add(event, NOW);

        Instant atTheEdge = NOW.plus(RecentEvents.WINDOW);
        assertThat(recent.after(event.id(), atTheEdge)).isPresent();

        Instant past = atTheEdge.plus(Duration.ofMillis(1));
        assertThat(recent.after(event.id(), past)).isEmpty();
    }

    @Test
    void keepsAnEventOnce() {
        PublicEvent first = event();
        PublicEvent second = event();
        recent.add(first, NOW);

        assertThat(recent.add(second, NOW)).isTrue();
        assertThat(recent.add(second, NOW)).isFalse();
        assertThat(recent.after(first.id(), NOW)).contains(List.of(second));
    }

    private static PublicEvent event() {
        return new PublicEvent(new EventId(UUID.randomUUID()), "charge.paid", NOW, "{}");
    }
}
