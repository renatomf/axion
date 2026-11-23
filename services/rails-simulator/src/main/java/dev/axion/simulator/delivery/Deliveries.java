package dev.axion.simulator.delivery;

import dev.axion.simulator.chaos.AppliedFault;
import dev.axion.simulator.chaos.ChaosRules;
import dev.axion.simulator.chaos.CreationFaults;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The messages owed to the core. A message is recorded in the transaction that settles what it notifies,
 * so a payment is never committed without the delivery that tells the core of it (rails simulator design,
 * section 6.1). The chaos rules applied at creation change when and how often it is sent (section 7.2).
 */
@Service
public class Deliveries {

    public static final String PENDING = "rails_sim.deliveries.pending";

    /**
     * How far apart a {@code reorder} rule releases the deliveries it held: the dispatcher sends what is due
     * concurrently, so deliveries released at one moment would reach the core in no particular order.
     */
    static final Duration RELEASE_GAP = Duration.ofSeconds(1);

    private static final Logger LOG = LoggerFactory.getLogger(Deliveries.class);

    private final DeliveryRepository deliveries;
    private final ChaosRules chaos;
    private final Clock clock;
    private final MeterRegistry meters;

    Deliveries(DeliveryRepository deliveries, ChaosRules chaos, Clock clock, MeterRegistry meters) {
        this.deliveries = deliveries;
        this.chaos = chaos;
        this.clock = clock;
        this.meters = meters;
        // Read from the database when the gauge is read: the one count every dispatcher agrees on.
        Gauge.builder(PENDING, deliveries, DeliveryRepository::countPending)
                .description("Deliveries owed to the core, due now or later")
                .register(meters);
    }

    /**
     * Records a message to deliver now, or later under a {@code delay} rule, in the caller's transaction;
     * under a {@code duplicate} rule, its copies too, due at the same time. Under a {@code reorder} rule it is
     * held instead, and the rule's last delivery releases every one the rule held, newest first.
     *
     * @param endToEndId the payment the message notifies
     * @param transactionId the transaction ID of the payment's BR Code, which rules may match
     * @param messageId the message's {@code messageId}
     * @param message the exact bytes to send on every attempt
     * @return the id of the delivery, not of its copies
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID schedule(String endToEndId, String transactionId, String messageId, byte[] message) {
        var id = UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        CreationFaults faults = chaos.atCreation(endToEndId, transactionId);
        CreationFaults.Hold hold = faults.hold();
        @Nullable
        Instant due = hold != null ? null : faults.delay().map(now::plus).orElse(now);
        deliveries.insert(id, endToEndId, messageId, Kind.ORIGINAL, message, now, due, faults.applied());
        for (int copy = 0; copy < faults.copies(); copy++) {
            deliveries.insert(
                    UUID.randomUUID(), endToEndId, messageId, Kind.DUPLICATE, message, now, due, faults.applied());
        }
        if (hold != null && hold.last()) {
            int released = deliveries.release(hold.rule(), now, RELEASE_GAP);
            LOG.atInfo()
                    .addKeyValue("rule", hold.rule())
                    .addKeyValue("released", released)
                    .log("Held deliveries released");
        }
        if (!faults.applied().isEmpty()) {
            countOnCommit(faults.applied());
            LOG.atInfo()
                    .addKeyValue("end_to_end_id", endToEndId)
                    .addKeyValue("message_id", messageId)
                    .addKeyValue(
                            "faults",
                            faults.applied().stream()
                                    .map(applied -> applied.fault().tag())
                                    .toList())
                    .log("Delivery faults applied");
        }
        return id;
    }

    /**
     * Sends a refused or failed delivery again: a new delivery of kind {@code redelivery}, with the stored
     * bytes, due now. The rules applied at creation do not apply to it; those at each attempt do. The
     * delivery redelivered keeps its status.
     */
    @Transactional
    Redelivery redeliver(UUID id) {
        return deliveries
                .find(id)
                .<Redelivery>map(stored -> {
                    if (stored.status() != DeliveryStatus.REFUSED && stored.status() != DeliveryStatus.FAILED) {
                        return new Redelivery.NotRedeliverable(stored.status());
                    }
                    var redelivery = UUID.randomUUID();
                    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                    deliveries.insert(
                            redelivery,
                            stored.endToEndId(),
                            stored.messageId(),
                            Kind.REDELIVERY,
                            stored.message(),
                            now,
                            now,
                            List.of());
                    return new Redelivery.Created(redelivery, stored.endToEndId());
                })
                .orElseGet(Redelivery.NotFound::new);
    }

    // Counted once committed: a delivery rolled back with its payment was never put through a fault.
    private void countOnCommit(List<AppliedFault> faults) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                faults.forEach(applied -> meters.counter(
                                Dispatcher.FAULTS, "fault", applied.fault().tag())
                        .increment());
            }
        });
    }

    /** The deliveries of a payment, oldest first, each with its attempts in order. */
    @Transactional(readOnly = true)
    public List<Delivery> of(String endToEndId) {
        return deliveries.findByEndToEndId(endToEndId);
    }
}
