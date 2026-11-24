package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.domain.UrlRefusal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link WebhooksApi#update} (events and webhooks design, section 6.3), against a real PostgreSQL. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, CommittedEvents.class})
class UpdateEndpointTest {

    private static final String URL = "https://orders.example.com/axion/webhooks";

    private static final String NEW_URL = "https://billing.example.com/hooks";

    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final CommittedEvents events;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    UpdateEndpointTest(WebhooksApi webhooks, DatabaseOwner owner, CommittedEvents events) {
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.events = events;
    }

    @Test
    void changesEveryField() {
        EndpointView created = create();

        EndpointView updated = updated(update(
                created.id(),
                new EndpointChanges(
                        Optional.of(NEW_URL),
                        Optional.of(Optional.of("Billing")),
                        Optional.of(Set.of("*")),
                        Optional.of(EndpointStatus.DISABLED))));

        assertThat(updated)
                .isEqualTo(new EndpointView(
                        created.id(),
                        NEW_URL,
                        Optional.of("Billing"),
                        Set.of("*"),
                        EndpointStatus.DISABLED,
                        Optional.of(DisabledReason.BY_TENANT),
                        created.createdAt()));
        assertThat(as(tenant, () -> webhooks.endpoint(created.id()))).contains(updated);
        assertThat(updatedAt(created.id())).isAfter(created.createdAt());
    }

    @Test
    void keepsWhatTheChangesLeaveOut() {
        EndpointView created = create();

        EndpointView updated = updated(update(created.id(), describe(Optional.of("Billing"))));

        assertThat(updated)
                .isEqualTo(new EndpointView(
                        created.id(),
                        URL,
                        Optional.of("Billing"),
                        created.enabledEvents(),
                        EndpointStatus.ENABLED,
                        Optional.empty(),
                        created.createdAt()));
    }

    @Test
    void removesTheDescription() {
        EndpointView created = create();

        assertThat(updated(update(created.id(), describe(Optional.empty()))).description())
                .isEmpty();
    }

    @Test
    void enablesAnEndpointTheTenantDisabled() {
        EndpointView created = create();
        update(created.id(), status(EndpointStatus.DISABLED));

        EndpointView enabled = updated(update(created.id(), status(EndpointStatus.ENABLED)));

        assertThat(enabled.status()).isEqualTo(EndpointStatus.ENABLED);
        assertThat(enabled.disabledReason()).isEmpty();
    }

    @Test
    void enablesAnEndpointDisabledForFailing() {
        EndpointView created = create();
        disableForFailing(created.id());

        EndpointView enabled = updated(update(created.id(), status(EndpointStatus.ENABLED)));

        assertThat(enabled.status()).isEqualTo(EndpointStatus.ENABLED);
        assertThat(enabled.disabledReason()).isEmpty();
    }

    @Test
    void leavesAnEndpointDisabledForFailingWithItsReason() {
        EndpointView created = create();
        disableForFailing(created.id());

        EndpointView updated = updated(update(created.id(), status(EndpointStatus.DISABLED)));

        assertThat(updated.disabledReason()).contains(DisabledReason.FAILING);
        assertThat(events.publications(tenant)).hasSize(1);
    }

    @Test
    void refusesANewUrlToAPrivateAddressAndChangesNothing() {
        EndpointView created = create();

        UpdateEndpointResult result = update(created.id(), url("https://hooks." + TestDns.PRIVATE + "/axion"));

        assertThat(result).isEqualTo(new UpdateEndpointResult.InvalidUrl(UrlRefusal.MUST_BE_PUBLIC));
        assertThat(as(tenant, () -> webhooks.endpoint(created.id()))).contains(created);
    }

    @Test
    void refusesANewUrlThatIsNotHttps() {
        EndpointView created = create();

        assertThat(update(created.id(), url("http://orders.example.com")))
                .isEqualTo(new UpdateEndpointResult.InvalidUrl(UrlRefusal.MUST_BE_HTTPS));
    }

    @Test
    void findsNoUnknownEndpoint() {
        assertThat(update(new WebhookEndpointId(UUID.randomUUID()), url(NEW_URL)))
                .isEqualTo(new UpdateEndpointResult.EndpointNotFound());
    }

    @Test
    void leavesAnotherTenantsEndpointAsItWas() {
        var other = new TenantId(UUID.randomUUID());
        EndpointView others = as(other, () -> created(webhooks.create(request())));

        assertThat(update(others.id(), url(NEW_URL))).isEqualTo(new UpdateEndpointResult.EndpointNotFound());
        assertThat(as(other, () -> webhooks.endpoint(others.id()))).contains(others);
    }

    @Test
    void publishesTheEndpointBeforeAndAfter() {
        EndpointView created = create();

        EndpointView updated = updated(update(created.id(), url(NEW_URL)));

        assertThat(events.of(tenant, WebhookEndpointUpdated.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.endpoint()).isEqualTo(updated);
                    assertThat(event.previous()).isEqualTo(created);
                    assertThat(event.header().tenant()).isEqualTo(tenant);
                    assertThat(event.header().actor()).isEqualTo(TestCaller.ACTOR);
                    assertThat(event.header().correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
                });
    }

    @Test
    void publishesNothingForChangesThatLeaveTheEndpointAsItWas() {
        EndpointView created = create();
        Instant before = updatedAt(created.id());

        EndpointView updated = updated(update(
                created.id(),
                new EndpointChanges(
                        Optional.of(URL),
                        Optional.of(Optional.empty()),
                        Optional.of(created.enabledEvents()),
                        Optional.of(EndpointStatus.ENABLED))));

        assertThat(updated).isEqualTo(created);
        assertThat(events.of(tenant, WebhookEndpointUpdated.class)).isEmpty();
        assertThat(updatedAt(created.id())).isEqualTo(before);
    }

    @Test
    void publishesNothingForARefusedUrl() {
        EndpointView created = create();

        update(created.id(), url("http://orders.example.com"));

        assertThat(events.of(tenant, WebhookEndpointUpdated.class)).isEmpty();
    }

    @Test
    void refusesChangesOutsideTheLimitsOfARegistration() {
        EndpointView created = create();
        WebhookEndpointId id = created.id();

        assertThatThrownBy(() -> update(id, describe(Optional.of("")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(id, url("https://orders.example.com/" + "a".repeat(2_048))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(id, events(Set.of("*", "charge.paid"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(id, events(Set.of("webhook_endpoint.disabled"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> update(id, events(Set.of()))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelsThePendingDeliveriesOfAnEndpointItDisables() {
        EndpointView created = create();
        var deliveries = new TestDeliveries(owner);
        UUID pending = deliveries.pending(tenant, created.id());

        update(created.id(), status(EndpointStatus.DISABLED));

        assertThat(deliveries.status(pending)).isEqualTo("CANCELED");
    }

    @Test
    void leavesThePendingDeliveriesOfAnEndpointItKeepsEnabled() {
        EndpointView created = create();
        var deliveries = new TestDeliveries(owner);
        UUID pending = deliveries.pending(tenant, created.id());

        update(created.id(), url(NEW_URL));

        assertThat(deliveries.status(pending)).isEqualTo("PENDING");
    }

    @Test
    void needsATenantBound() {
        assertThatThrownBy(() -> webhooks.update(new WebhookEndpointId(UUID.randomUUID()), url(NEW_URL)))
                .isInstanceOf(IllegalStateException.class);
    }

    private EndpointView create() {
        return as(tenant, () -> created(webhooks.create(request())));
    }

    private UpdateEndpointResult update(WebhookEndpointId id, EndpointChanges changes) {
        return as(tenant, () -> webhooks.update(id, changes));
    }

    private static <T> T as(TenantId caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(caller, Environment.TEST, work);
    }

    private static EndpointView created(CreateEndpointResult result) {
        assertThat(result).isInstanceOf(CreateEndpointResult.Created.class);
        return ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    private static EndpointView updated(UpdateEndpointResult result) {
        assertThat(result).isInstanceOf(UpdateEndpointResult.Updated.class);
        return ((UpdateEndpointResult.Updated) result).endpoint();
    }

    private static EndpointRequest request() {
        return new EndpointRequest(URL, Optional.empty(), Set.of("charge.paid"));
    }

    private static EndpointChanges url(String url) {
        return new EndpointChanges(Optional.of(url), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static EndpointChanges describe(Optional<String> description) {
        return new EndpointChanges(Optional.empty(), Optional.of(description), Optional.empty(), Optional.empty());
    }

    private static EndpointChanges events(Set<String> types) {
        return new EndpointChanges(Optional.empty(), Optional.empty(), Optional.of(types), Optional.empty());
    }

    private static EndpointChanges status(EndpointStatus status) {
        return new EndpointChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(status));
    }

    // What the delivery job will do (section 8.6), arranged by the owner.
    private void disableForFailing(WebhookEndpointId id) {
        owner.sql("UPDATE webhooks.endpoints SET status = 'DISABLED', disabled_reason = 'FAILING' WHERE id = ?")
                .param(id.value())
                .update();
    }

    private Instant updatedAt(WebhookEndpointId id) {
        return owner.sql("SELECT updated_at FROM webhooks.endpoints WHERE id = ?")
                .param(id.value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }
}
