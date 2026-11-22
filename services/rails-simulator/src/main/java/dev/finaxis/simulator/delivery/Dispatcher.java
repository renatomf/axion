package dev.finaxis.simulator.delivery;

import dev.finaxis.simulator.chaos.AttemptFaults;
import dev.finaxis.simulator.chaos.ChaosRules;
import dev.finaxis.simulator.delivery.DeliveryRepository.Due;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the due deliveries to the core, at least once each (rails simulator design, section 6.1): claim
 * a batch under a lease, send each outside any transaction, record what came of it, and schedule a retry
 * when the core did not accept it (section 6.2). A crash between sending and recording sends the message
 * again when the lease ends; the core deduplicates. The chaos rules matching an attempt change how it is
 * sent (section 7.2), and deliveries a {@code reorder} rule no longer in force left held are released.
 */
@Component
@ConditionalOnBooleanProperty(name = "simulator.dispatcher.enabled", matchIfMissing = true)
class Dispatcher {

    static final String ATTEMPTS = "rails_sim.delivery.attempts";
    static final String FAULTS = "rails_sim.faults.applied";

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    private static final int BATCH = 16;
    private static final Duration LEASE = Duration.ofSeconds(30);

    /**
     * How long after a {@code reorder} rule held its last delivery the deliveries it still holds are taken as
     * abandoned: by then that delivery's transaction, which released the others, has long committed, and
     * what is left came from a payment committed after it.
     */
    private static final Duration SETTLED = Duration.ofSeconds(5);

    private final DeliveryRepository deliveries;
    private final CoreClient core;
    private final ChaosRules chaos;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RetryPolicy retries = new RetryPolicy(RandomGenerator.getDefault());

    Dispatcher(DeliveryRepository deliveries, CoreClient core, ChaosRules chaos, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.core = core;
        this.chaos = chaos;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelay = 250, timeUnit = TimeUnit.MILLISECONDS)
    void dispatch() {
        Instant now = now();
        releaseAbandoned(now);
        List<Due> due = deliveries.claimDue(now, now.plus(LEASE), BATCH);
        // Concurrent and unordered, as on the rails; closing the executor waits for every send.
        try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(delivery -> senders.execute(() -> deliver(delivery)));
        }
    }

    /**
     * Releases what {@code reorder} rules hold when no rule will: one removed, or lost in a restart, and one
     * whose last delivery was committed before a payment it had held. A rule in force, or whose last
     * delivery may still be committing, is left to release its deliveries itself.
     */
    private void releaseAbandoned(Instant now) {
        Set<UUID> inForce = chaos.holding();
        for (UUID rule : deliveries.holdingRules()) {
            boolean settling = chaos.completedAt(rule)
                    .map(completed -> now.isBefore(completed.plus(SETTLED)))
                    .orElse(false);
            if (inForce.contains(rule) || settling) {
                continue;
            }
            int released = deliveries.release(rule, now, Deliveries.RELEASE_GAP);
            LOG.atInfo()
                    .addKeyValue("rule", rule)
                    .addKeyValue("released", released)
                    .log("Abandoned held deliveries released");
        }
    }

    private void deliver(Due delivery) {
        int attempt = delivery.attempts() + 1;
        AttemptFaults faults = chaos.atAttempt(delivery.endToEndId(), delivery.transactionId(), attempt);
        faults.latency().ifPresent(Dispatcher::sleep);
        CoreClient.Response response = faults.fails()
                ? CoreClient.Response.failed(CoreClient.Response.NOT_SENT)
                : core.send(delivery.message(), delivery.messageId());
        Instant attemptedAt = now();
        // A lost answer, whatever it said, leaves the simulator as if none came: retried, without a Retry-After.
        DeliveryOutcome outcome = faults.losesResponse() ? DeliveryOutcome.RETRIED : DeliveryOutcome.of(response);
        String retryAfter = faults.losesResponse() ? null : response.retryAfter();
        Optional<Instant> nextAttemptAt = Optional.empty();
        if (outcome == DeliveryOutcome.RETRIED) {
            nextAttemptAt = retries.nextAttempt(delivery.createdAt(), attemptedAt, attempt, retryAfter);
            if (nextAttemptAt.isEmpty()) {
                outcome = DeliveryOutcome.FAILED;
            }
        }
        deliveries.recordAttempt(
                delivery, attemptedAt, response, outcome, nextAttemptAt.orElse(null), faults.applied());
        meters.counter(ATTEMPTS, "outcome", outcome.tag()).increment();
        faults.applied()
                .forEach(applied ->
                        meters.counter(FAULTS, "fault", applied.fault().tag()).increment());
        LOG.atInfo()
                .addKeyValue("end_to_end_id", delivery.endToEndId())
                .addKeyValue("message_id", delivery.messageId())
                .addKeyValue("attempt", attempt)
                .addKeyValue("outcome", outcome.tag())
                .addKeyValue("response_status", response.status())
                .addKeyValue("error", response.error())
                .addKeyValue(
                        "faults",
                        faults.applied().stream()
                                .map(applied -> applied.fault().tag())
                                .toList())
                .log("Delivery attempt");
    }

    private static void sleep(Duration latency) {
        try {
            Thread.sleep(latency);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
