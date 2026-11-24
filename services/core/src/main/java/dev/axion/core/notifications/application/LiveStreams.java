package dev.axion.core.notifications.application;

import dev.axion.core.events.PublicEvent;
import dev.axion.core.notifications.domain.RecentEvents;
import dev.axion.core.notifications.domain.StreamPermissions;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.PublicId;
import dev.axion.core.shared.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live streams open on this instance, by tenant, with each tenant's recent events (events and
 * webhooks design, section 10). Phase 1 runs one instance of the core, so handing an event to the
 * streams kept here reaches every one.
 *
 * <p>A tenant's recent events are kept while it has a stream open, and for {@link RecentEvents#WINDOW}
 * after its last one closes, so a client that reconnects can resume. An event of a tenant with no
 * stream in that time is not kept: no client could ask for it.
 *
 * <p>One lock guards them all: opening, closing and publishing only add to maps and queues, and never
 * wait on a client, so it is held briefly.
 */
@Component
public class LiveStreams {

    private static final Logger LOG = LoggerFactory.getLogger(LiveStreams.class);

    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<TenantId, TenantStreams> tenants = new HashMap<>();

    LiveStreams(Clock clock) {
        this.clock = clock;
    }

    /**
     * Opens a stream for the member. With the ID of the last event its client received, the stream
     * first sends the tenant's events since, or {@link StreamMessage.Reset} when they are no longer
     * all kept.
     */
    public void open(StreamMember member, StreamSink sink, Optional<String> lastEventId) {
        Instant now = clock.instant();
        lock.lock();
        try {
            TenantStreams tenant = tenants.computeIfAbsent(member.tenant(), id -> new TenantStreams());
            lastEventId.ifPresent(last -> resume(tenant, member, sink, last, now));
            tenant.streams.put(sink, member);
        } finally {
            lock.unlock();
        }
    }

    /** Forgets the stream, once its connection has ended or is ending. */
    public void close(StreamMember member, StreamSink sink) {
        lock.lock();
        try {
            TenantStreams tenant = tenants.get(member.tenant());
            if (tenant != null && tenant.streams.remove(sink) != null && tenant.streams.isEmpty()) {
                tenant.lastClosedAt = clock.instant();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hands the event to each of the tenant's streams whose member may receive its type, unless they
     * were already handed it. A stream whose queue is full is closed: its client resumes when it
     * reconnects.
     */
    void publish(TenantId tenantId, PublicEvent event) {
        Instant now = clock.instant();
        List<StreamSink> full = new ArrayList<>();
        lock.lock();
        try {
            TenantStreams tenant = tenants.get(tenantId);
            if (tenant == null) {
                return;
            }
            if (tenant.isIdleSince(now)) {
                tenants.remove(tenantId);
                return;
            }
            if (!tenant.recent.add(event, now)) {
                return;
            }
            tenant.streams.forEach((sink, member) -> {
                if (!offer(sink, member, event)) {
                    full.add(sink);
                }
            });
        } finally {
            lock.unlock();
        }
        for (StreamSink sink : full) {
            LOG.info("Closing a live stream of tenant {} whose client is not reading", tenantId.publicId());
            sink.close();
        }
    }

    private static void resume(TenantStreams tenant, StreamMember member, StreamSink sink, String last, Instant now) {
        Optional<List<PublicEvent>> missed = parse(last).flatMap(id -> tenant.recent.after(id, now));
        if (missed.isEmpty()) {
            sink.offer(new StreamMessage.Reset());
            return;
        }
        // A new stream's queue holds more than the events kept, so every one fits.
        missed.get().forEach(event -> offer(sink, member, event));
    }

    private static boolean offer(StreamSink sink, StreamMember member, PublicEvent event) {
        return !StreamPermissions.allows(member.permissions(), event.type())
                || sink.offer(new StreamMessage.Event(event));
    }

    // An ID the core did not send, malformed or not, is one it no longer keeps.
    private static Optional<EventId> parse(String text) {
        try {
            return Optional.of(new EventId(PublicId.parse(EventId.PREFIX, text).uuid()));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    private static final class TenantStreams {

        private final Map<StreamSink, StreamMember> streams = new LinkedHashMap<>();
        private final RecentEvents recent = new RecentEvents();
        private @Nullable Instant lastClosedAt;

        /** No stream open, and none closed within the window: no client can resume. */
        boolean isIdleSince(Instant now) {
            return streams.isEmpty() && lastClosedAt != null && lastClosedAt.isBefore(now.minus(RecentEvents.WINDOW));
        }
    }
}
