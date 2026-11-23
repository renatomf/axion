package dev.axion.core.events;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.modulith.events.ApplicationModuleListener;

/**
 * A listener of the tests, run as every consumer runs: after the commit, on the listeners' executor,
 * through the publication registry. A probe can be told to fail its first handlings, as a consumer
 * whose dependency is down. Tests that share it publish probes of their own IDs.
 */
@TestComponent
public class Probes {

    /** An event of the tests; its ID tells the handlings of one test from another's. */
    public record Probe(UUID id) {

        public static Probe next() {
            return new Probe(UUID.randomUUID());
        }
    }

    /** Where and how often a probe was handled. */
    public record Handling(String thread, boolean virtual) {}

    private final Map<UUID, List<Handling>> handled = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> failuresLeft = new ConcurrentHashMap<>();

    @ApplicationModuleListener
    void handle(Probe probe) {
        var failures = failuresLeft.get(probe.id());
        if (failures != null && failures.getAndDecrement() > 0) {
            throw new IllegalStateException("Probe " + probe.id() + " fails as told");
        }
        Thread current = Thread.currentThread();
        handled.computeIfAbsent(probe.id(), id -> new CopyOnWriteArrayList<>())
                .add(new Handling(current.getName(), current.isVirtual()));
    }

    /** Makes the probe's next handlings fail, so its publication is left incomplete. */
    public void failNext(Probe probe, int times) {
        failuresLeft.put(probe.id(), new AtomicInteger(times));
    }

    /** The probe's successful handlings, in order. */
    public List<Handling> handlings(Probe probe) {
        return handled.getOrDefault(probe.id(), List.of());
    }
}
