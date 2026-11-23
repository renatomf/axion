package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.events.EventFixtures;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointChanges;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The deliveries recorded for each public event (events and webhooks design, section 8.1), against a
 * real PostgreSQL. Most tests hand the listener an event themselves; one follows a charge's event
 * from its commit.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
class DeliveryRecordingTest {

    private final DeliveryRecording recording;
    private final WebhooksApi webhooks;
    private final EventCatalog catalog;
    private final JdbcClient owner;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    DeliveryRecordingTest(
            DeliveryRecording recording, WebhooksApi webhooks, EventCatalog catalog, DatabaseOwner owner) {
        this.recording = recording;
        this.webhooks = webhooks;
        this.catalog = catalog;
        this.owner = owner.jdbc();
    }

    @Test
    void recordsTheEventAsTheCatalogRendersIt() {
        ChargePaid paid = paid(tenant);

        recording.record(paid);

        assertThat(owner.sql("SELECT tenant_id, type, body FROM webhooks.events WHERE id = ?")
                        .param(paid.id().value())
                        .query((row, number) -> List.of(
                                row.getObject("tenant_id", UUID.class),
                                row.getString("type"),
                                new String(row.getBytes("body"), StandardCharsets.UTF_8)))
                        .single())
                .containsExactly(
                        tenant.value(),
                        "charge.paid",
                        catalog.render(paid).orElseThrow().body());
    }

    @Test
    void deliversToEachEnabledEndpointThatReceivesTheType() {
        WebhookEndpointId subscribed = endpoint(tenant, Set.of("charge.paid", "charge.refunded"));
        WebhookEndpointId everything = endpoint(tenant, Set.of("*"));
        endpoint(tenant, Set.of("charge.created"));
        ChargePaid paid = paid(tenant);

        recording.record(paid);

        assertThat(pendingDeliveries(paid.id())).containsExactlyInAnyOrder(subscribed.value(), everything.value());
    }

    @Test
    void deliversToNoDisabledOrDeletedEndpoint() {
        WebhookEndpointId disabled = endpoint(tenant, Set.of("*"));
        TestCaller.run(tenant, Environment.TEST, () -> webhooks.update(disabled, disable()));
        WebhookEndpointId deleted = endpoint(tenant, Set.of("*"));
        TestCaller.run(tenant, Environment.TEST, () -> webhooks.delete(deleted));
        ChargePaid paid = paid(tenant);

        recording.record(paid);

        assertThat(pendingDeliveries(paid.id())).isEmpty();
    }

    @Test
    void deliversToNoEndpointOfAnotherTenant() {
        endpoint(new TenantId(UUID.randomUUID()), Set.of("*"));
        ChargePaid paid = paid(tenant);

        recording.record(paid);

        assertThat(pendingDeliveries(paid.id())).isEmpty();
    }

    @Test
    void recordsNothingMoreWhenHandedTheSameEventAgain() {
        endpoint(tenant, Set.of("*"));
        ChargePaid paid = paid(tenant);
        recording.record(paid);

        recording.record(paid);

        assertThat(pendingDeliveries(paid.id())).hasSize(1);
    }

    @Test
    void sendsNoEarlierEventToAnEndpointCreatedLater() {
        ChargePaid paid = paid(tenant);
        recording.record(paid);
        endpoint(tenant, Set.of("*"));

        recording.record(paid);

        assertThat(pendingDeliveries(paid.id())).isEmpty();
    }

    @Test
    void recordsOneDeliveryPerEndpointWhenHandedTheSameEventConcurrently() throws Exception {
        endpoint(tenant, Set.of("*"));
        ChargePaid paid = paid(tenant);
        var start = new CountDownLatch(1);
        Callable<Void> handling = () -> {
            start.await();
            recording.record(paid);
            return null;
        };

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(handling);
            var second = threads.submit(handling);
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(pendingDeliveries(paid.id())).hasSize(1);
    }

    @Test
    void recordsTheDeliveriesOfAChargeOnceItsCreationCommits(
            @Autowired TenancyApi tenancy, @Autowired BillingApi billing) {
        NewOrganization organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID());
        TenantId owner = organization.test();
        WebhookEndpointId endpoint = endpoint(owner, Set.of("charge.created"));

        var charge = BillingFixtures.created(TestCaller.call(
                owner, Environment.TEST, () -> billing.createCharge(ChargeRequest.of(BillingFixtures.brl(10_000)))));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(this.owner
                                .sql("""
                                        SELECT d.endpoint_id FROM webhooks.deliveries d
                                          JOIN webhooks.events e ON e.id = d.event_id
                                         WHERE e.type = 'charge.created'
                                           AND convert_from(e.body, 'UTF8')::jsonb ->> 'subject' = ?
                                        """)
                                .param(charge.id().publicId().toString())
                                .query(UUID.class)
                                .list())
                        .containsExactly(endpoint.value()));
    }

    private List<UUID> pendingDeliveries(EventId event) {
        return owner.sql("SELECT endpoint_id FROM webhooks.deliveries WHERE event_id = ? AND status = 'PENDING'")
                .param(event.value())
                .query((row, number) -> row.getObject("endpoint_id", UUID.class))
                .list();
    }

    private WebhookEndpointId endpoint(TenantId owner, Set<String> types) {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), types);
        var result = TestCaller.call(owner, Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    private static EndpointChanges disable() {
        return new EndpointChanges(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(EndpointStatus.DISABLED));
    }

    // The fixtures' charge.paid, of the tenant and with an ID of its own.
    private static ChargePaid paid(TenantId tenant) {
        ChargePaid fixture = EventFixtures.one(ChargePaid.class);
        EventHeader header = fixture.header();
        return new ChargePaid(
                new EventHeader(
                        new EventId(UUID.randomUUID()),
                        tenant,
                        Environment.TEST,
                        header.actor(),
                        header.correlationId(),
                        header.occurredAt()),
                fixture.charge());
    }
}
