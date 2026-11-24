package dev.axion.core.notifications.application;

import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.shared.DomainEvent;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Hands each domain event of the stream's types to its tenant's live streams, as the catalog writes
 * it (events and webhooks design, section 10). The stream keeps no table, so the listeners run
 * without a transaction and bind no tenant.
 */
@Component
class StreamPublishing {

    private final EventCatalog catalog;
    private final LiveStreams streams;

    StreamPublishing(EventCatalog catalog, LiveStreams streams) {
        this.catalog = catalog;
        this.streams = streams;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeCreated event) {
        publish(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargePaid event) {
        publish(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeExpired event) {
        publish(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(PaymentFlagged event) {
        publish(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeRefunded event) {
        publish(event);
    }

    void publish(DomainEvent event) {
        streams.publish(
                event.tenant(),
                catalog.render(event)
                        .orElseThrow(() -> new IllegalArgumentException(
                                event.getClass().getSimpleName() + " has no public type to stream")));
    }
}
