package dev.axion.core.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.MutableClock;
import dev.axion.core.events.PublicEvent;
import dev.axion.core.notifications.domain.RecentEvents;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveStreamsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:00:00Z");

    private final MutableClock clock = new MutableClock(NOW);

    private final LiveStreams streams = new LiveStreams(clock);

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Test
    void handsAnEventToEveryStreamOfItsTenant() {
        TestSink first = open(member(tenant, Permission.CHARGES_READ));
        TestSink second = open(member(tenant, Permission.CHARGES_READ));
        PublicEvent event = event("charge.paid");

        streams.publish(tenant, event);

        assertThat(first.events()).containsExactly(event);
        assertThat(second.events()).containsExactly(event);
    }

    @Test
    void handsNothingToAnotherTenantsStreams() {
        TestSink other = open(member(new TenantId(UUID.randomUUID()), Permission.CHARGES_READ));

        streams.publish(tenant, event("charge.paid"));

        assertThat(other.events()).isEmpty();
    }

    @Test
    void handsAMemberOnlyTheTypesTheirPermissionsAllow() {
        TestSink withoutCharges = open(member(tenant, Permission.WEBHOOKS_MANAGE));

        streams.publish(tenant, event("charge.paid"));
        streams.publish(tenant, event("charge.unknown"));

        assertThat(withoutCharges.events()).isEmpty();
    }

    @Test
    void handsNothingToAClosedStream() {
        StreamMember member = member(tenant, Permission.CHARGES_READ);
        TestSink sink = open(member);

        streams.close(member, sink);
        streams.publish(tenant, event("charge.paid"));

        assertThat(sink.events()).isEmpty();
    }

    @Test
    void closesAStreamWhoseQueueIsFull() {
        TestSink full = open(member(tenant, Permission.CHARGES_READ), 1);
        TestSink reading = open(member(tenant, Permission.CHARGES_READ));

        streams.publish(tenant, event("charge.created"));
        streams.publish(tenant, event("charge.paid"));

        assertThat(full.closed).isTrue();
        assertThat(full.events()).hasSize(1);
        assertThat(reading.closed).isFalse();
        assertThat(reading.events()).hasSize(2);
    }

    @Test
    void sendsAResumingStreamTheEventsItMissedThatItsMemberMayReceive() {
        StreamMember member = member(tenant, Permission.CHARGES_READ);
        TestSink first = open(member);
        PublicEvent received = event("charge.created");
        PublicEvent missed = event("charge.paid");
        streams.publish(tenant, received);
        streams.close(member, first);
        streams.publish(tenant, missed);
        streams.publish(tenant, event("charge.unknown"));

        TestSink resumed = resume(member, received.id().publicId().toString());

        assertThat(resumed.messages()).containsExactly(new StreamMessage.Event(missed));
    }

    @Test
    void resetsAResumingStreamWhoseLastEventIsNoLongerKept() {
        StreamMember member = member(tenant, Permission.CHARGES_READ);
        open(member);
        PublicEvent received = event("charge.created");
        streams.publish(tenant, received);
        clock.advance(RecentEvents.WINDOW.plusSeconds(1));

        TestSink resumed = resume(member, received.id().publicId().toString());

        assertThat(resumed.messages()).containsExactly(new StreamMessage.Reset());
    }

    @Test
    void resetsAResumingStreamWithAnIdTheCoreDidNotSend() {
        StreamMember member = member(tenant, Permission.CHARGES_READ);

        assertThat(resume(member, "evt_not-an-id").messages()).containsExactly(new StreamMessage.Reset());
    }

    @Test
    void sendsAnEventOnceWhenItsListenerIsHandedItTwice() {
        TestSink sink = open(member(tenant, Permission.CHARGES_READ));
        PublicEvent event = event("charge.paid");

        streams.publish(tenant, event);
        streams.publish(tenant, event);

        assertThat(sink.events()).containsExactly(event);
    }

    @Test
    void keepsNoEventOfATenantWhoseLastStreamClosedBeforeTheWindow() {
        StreamMember member = member(tenant, Permission.CHARGES_READ);
        TestSink first = open(member);
        PublicEvent received = event("charge.created");
        streams.publish(tenant, received);
        streams.close(member, first);
        clock.advance(RecentEvents.WINDOW.plus(Duration.ofSeconds(1)));
        streams.publish(tenant, event("charge.paid"));

        TestSink resumed = resume(member, received.id().publicId().toString());

        assertThat(resumed.messages()).containsExactly(new StreamMessage.Reset());
    }

    private TestSink open(StreamMember member) {
        return open(member, Integer.MAX_VALUE);
    }

    private TestSink open(StreamMember member, int capacity) {
        TestSink sink = new TestSink(capacity);
        streams.open(member, sink, Optional.empty());
        return sink;
    }

    private TestSink resume(StreamMember member, String lastEventId) {
        TestSink sink = new TestSink(Integer.MAX_VALUE);
        streams.open(member, sink, Optional.of(lastEventId));
        return sink;
    }

    private static StreamMember member(TenantId tenant, Permission permission) {
        return new StreamMember(tenant, new MemberId(UUID.randomUUID()), Set.of(permission));
    }

    private static PublicEvent event(String type) {
        return new PublicEvent(new EventId(UUID.randomUUID()), type, NOW, "{}");
    }

    /** A client's connection that holds up to its capacity of messages. */
    static final class TestSink implements StreamSink {

        private final int capacity;
        private final List<StreamMessage> messages = new ArrayList<>();
        private boolean closed;

        TestSink(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public boolean offer(StreamMessage message) {
            if (messages.size() == capacity) {
                return false;
            }
            messages.add(message);
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }

        List<StreamMessage> messages() {
            return List.copyOf(messages);
        }

        List<PublicEvent> events() {
            return messages.stream()
                    .filter(StreamMessage.Event.class::isInstance)
                    .map(message -> ((StreamMessage.Event) message).event())
                    .toList();
        }
    }
}
