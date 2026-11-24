package dev.axion.core.notifications.application;

import dev.axion.core.events.PublicEvent;
import dev.axion.core.notifications.domain.StreamPermissions;
import dev.axion.core.shared.TenantId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live streams open on this instance, by tenant (events and webhooks design, section 10). Phase 1
 * runs one instance of the core, so handing an event to the streams kept here reaches every one.
 *
 * <p>One lock guards them all: opening, closing and publishing only add to maps and queues, and never
 * wait on a client, so it is held briefly.
 */
@Component
public class LiveStreams {

    private static final Logger LOG = LoggerFactory.getLogger(LiveStreams.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<TenantId, Map<StreamSink, StreamMember>> open = new HashMap<>();

    public void open(StreamMember member, StreamSink sink) {
        lock.lock();
        try {
            open.computeIfAbsent(member.tenant(), tenant -> new LinkedHashMap<>())
                    .put(sink, member);
        } finally {
            lock.unlock();
        }
    }

    /** Forgets the stream, once its connection has ended or is ending. */
    public void close(StreamMember member, StreamSink sink) {
        lock.lock();
        try {
            Map<StreamSink, StreamMember> streams = open.get(member.tenant());
            if (streams != null) {
                streams.remove(sink);
                if (streams.isEmpty()) {
                    open.remove(member.tenant());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Hands the event to each of the tenant's streams whose member may receive its type. A stream
     * whose queue is full is closed: its client resumes when it reconnects.
     */
    void publish(TenantId tenant, PublicEvent event) {
        List<StreamSink> full = new ArrayList<>();
        lock.lock();
        try {
            Map<StreamSink, StreamMember> streams = open.getOrDefault(tenant, Map.of());
            streams.forEach((sink, member) -> {
                if (StreamPermissions.allows(member.permissions(), event.type())
                        && !sink.offer(new StreamMessage.Event(event))) {
                    full.add(sink);
                }
            });
        } finally {
            lock.unlock();
        }
        for (StreamSink sink : full) {
            LOG.info("Closing a live stream of tenant {} whose client is not reading", tenant.publicId());
            sink.close();
        }
    }
}
