package dev.axion.core.webhooks.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.events.PublicEvent;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.persistence.JdbcDeliveries;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turns each domain event with a public type into the deliveries that send it (events and webhooks
 * design, section 8.1): the public event, recorded once, and one pending delivery per endpoint of
 * the tenant that receives its type. Endpoints created later receive none of the earlier events.
 *
 * <p>Each listener runs without a transaction of its own, so the event's tenant is bound before
 * the transaction begins: Row-Level Security reads it then (tenancy design, section 5.3).
 */
@Component
class DeliveryRecording {

    private static final String ROLE = "axion_webhooks";

    private final EventCatalog catalog;
    private final JdbcDeliveries deliveries;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final UuidV7Generator ids;

    DeliveryRecording(
            EventCatalog catalog,
            JdbcDeliveries deliveries,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock) {
        this.catalog = catalog;
        this.deliveries = deliveries;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeCreated event) {
        record(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargePaid event) {
        record(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeExpired event) {
        record(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(PaymentFlagged event) {
        record(event);
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeRefunded event) {
        record(event);
    }

    /** Records the event and its deliveries; a second handling of the same event records nothing. */
    void record(DomainEvent event) {
        PublicEvent published = catalog.render(event)
                .orElseThrow(() -> new IllegalArgumentException(
                        event.getClass().getSimpleName() + " has no public type to deliver"));
        TenantContext.run(
                event.tenant(),
                event.environment(),
                () -> transactions.executeWithoutResult(transaction -> roles.call(ROLE, () -> {
                    if (deliveries.insertEvent(event.tenant(), published)) {
                        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
                        for (WebhookEndpointId endpoint : deliveries.lockReceivers(event.tenant(), published.type())) {
                            deliveries.insertDelivery(ids.next(), event.tenant(), endpoint, published, now);
                        }
                    }
                    return null;
                })));
    }
}
