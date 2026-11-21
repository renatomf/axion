package dev.finaxis.simulator.delivery;

import dev.finaxis.simulator.delivery.DeliveryRepository.Due;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
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
 * again when the lease ends; the core deduplicates.
 */
@Component
@ConditionalOnBooleanProperty(name = "simulator.dispatcher.enabled", matchIfMissing = true)
class Dispatcher {

    static final String ATTEMPTS = "rails_sim.delivery.attempts";

    private static final Logger LOG = LoggerFactory.getLogger(Dispatcher.class);

    private static final int BATCH = 16;
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final DeliveryRepository deliveries;
    private final CoreClient core;
    private final Clock clock;
    private final MeterRegistry meters;
    private final RetryPolicy retries = new RetryPolicy(RandomGenerator.getDefault());

    Dispatcher(DeliveryRepository deliveries, CoreClient core, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.core = core;
        this.clock = clock;
        this.meters = meters;
    }

    @Scheduled(fixedDelay = 250, timeUnit = TimeUnit.MILLISECONDS)
    void dispatch() {
        Instant now = now();
        List<Due> due = deliveries.claimDue(now, now.plus(LEASE), BATCH);
        // Concurrent and unordered, as on the rails; closing the executor waits for every send.
        try (var senders = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(delivery -> senders.execute(() -> deliver(delivery)));
        }
    }

    private void deliver(Due delivery) {
        CoreClient.Response response = core.send(delivery.message(), delivery.messageId());
        Instant attemptedAt = now();
        DeliveryOutcome outcome = DeliveryOutcome.of(response);
        Optional<Instant> nextAttemptAt = Optional.empty();
        if (outcome == DeliveryOutcome.RETRIED) {
            nextAttemptAt = retries.nextAttempt(
                    delivery.createdAt(), attemptedAt, delivery.attempts() + 1, response.retryAfter());
            if (nextAttemptAt.isEmpty()) {
                outcome = DeliveryOutcome.FAILED;
            }
        }
        deliveries.recordAttempt(delivery, attemptedAt, response, outcome, nextAttemptAt.orElse(null));
        meters.counter(ATTEMPTS, "outcome", outcome.tag()).increment();
        LOG.atInfo()
                .addKeyValue("end_to_end_id", delivery.endToEndId())
                .addKeyValue("message_id", delivery.messageId())
                .addKeyValue("attempt", delivery.attempts() + 1)
                .addKeyValue("outcome", outcome.tag())
                .addKeyValue("response_status", response.status())
                .addKeyValue("error", response.error())
                .log("Delivery attempt");
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
