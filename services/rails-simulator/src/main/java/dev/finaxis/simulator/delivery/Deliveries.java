package dev.finaxis.simulator.delivery;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The messages owed to the core. A message is recorded in the transaction that settles what it notifies,
 * so a payment is never committed without the delivery that tells the core of it (rails simulator design,
 * section 6.1).
 */
@Service
public class Deliveries {

    private final DeliveryRepository deliveries;
    private final Clock clock;

    Deliveries(DeliveryRepository deliveries, Clock clock) {
        this.deliveries = deliveries;
        this.clock = clock;
    }

    /**
     * Records a message to deliver now, in the caller's transaction.
     *
     * @param endToEndId the payment the message notifies
     * @param messageId the message's {@code messageId}
     * @param message the exact bytes to send on every attempt
     * @return the delivery's id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID schedule(String endToEndId, String messageId, byte[] message) {
        var id = UUID.randomUUID();
        deliveries.insert(id, endToEndId, messageId, message, clock.instant().truncatedTo(ChronoUnit.MICROS));
        return id;
    }
}
