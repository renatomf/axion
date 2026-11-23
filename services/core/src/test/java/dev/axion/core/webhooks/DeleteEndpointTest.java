package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link WebhooksApi#delete} (events and webhooks design, section 6.3), against a real PostgreSQL. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, CommittedEvents.class})
class DeleteEndpointTest {

    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final CommittedEvents events;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    DeleteEndpointTest(WebhooksApi webhooks, DatabaseOwner owner, CommittedEvents events) {
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.events = events;
    }

    @Test
    void deletesAnEndpointAndKeepsItsRow() {
        EndpointView created = create();

        assertThat(delete(created.id())).contains(created);
        assertThat(owner.sql("SELECT deleted_at IS NOT NULL FROM webhooks.endpoints WHERE id = ?")
                        .param(created.id().value())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void neitherReadsNorListsADeletedEndpoint() {
        EndpointView deleted = create();
        EndpointView kept = create();
        delete(deleted.id());

        assertThat(as(tenant, () -> webhooks.endpoint(deleted.id()))).isEmpty();
        assertThat(as(tenant, () -> webhooks.endpoints(EndpointCursor.first(), 10))
                        .endpoints())
                .containsExactly(kept);
    }

    @Test
    void changesNoDeletedEndpoint() {
        EndpointView created = create();
        delete(created.id());

        var changes = new EndpointChanges(
                Optional.of("https://billing.example.com/hooks"), Optional.empty(), Optional.empty(), Optional.empty());
        assertThat(as(tenant, () -> webhooks.update(created.id(), changes)))
                .isEqualTo(new UpdateEndpointResult.EndpointNotFound());
    }

    @Test
    void freesItsPlaceInTheLimit() {
        EndpointView first = create();
        for (int i = 1; i < WebhooksApi.MAX_ENDPOINTS; i++) {
            create();
        }
        delete(first.id());

        assertThat(as(tenant, () -> webhooks.create(request()))).isInstanceOf(CreateEndpointResult.Created.class);
    }

    @Test
    void deletesAnEndpointOnce() {
        EndpointView created = create();
        delete(created.id());

        assertThat(delete(created.id())).isEmpty();
        assertThat(events.of(tenant, WebhookEndpointDeleted.class)).hasSize(1);
    }

    @Test
    void findsNoUnknownEndpoint() {
        assertThat(delete(new WebhookEndpointId(UUID.randomUUID()))).isEmpty();
        assertThat(events.publications(tenant)).isEmpty();
    }

    @Test
    void leavesAnotherTenantsEndpointInPlace() {
        var other = new TenantId(UUID.randomUUID());
        EndpointView others = as(other, () -> created(webhooks.create(request())));

        assertThat(delete(others.id())).isEmpty();
        assertThat(as(other, () -> webhooks.endpoint(others.id()))).contains(others);
    }

    @Test
    void publishesTheEndpointAsItWas() {
        EndpointView created = create();

        delete(created.id());

        assertThat(events.of(tenant, WebhookEndpointDeleted.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.endpoint()).isEqualTo(created);
                    assertThat(event.header().tenant()).isEqualTo(tenant);
                    assertThat(event.header().actor()).isEqualTo(TestCaller.ACTOR);
                    assertThat(event.header().correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
                });
    }

    @Test
    void needsATenantBound() {
        assertThatThrownBy(() -> webhooks.delete(new WebhookEndpointId(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    private EndpointView create() {
        return as(tenant, () -> created(webhooks.create(request())));
    }

    private Optional<EndpointView> delete(WebhookEndpointId id) {
        return as(tenant, () -> webhooks.delete(id));
    }

    private static <T> T as(TenantId caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(caller, Environment.TEST, work);
    }

    private static EndpointView created(CreateEndpointResult result) {
        assertThat(result).isInstanceOf(CreateEndpointResult.Created.class);
        return ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    private static EndpointRequest request() {
        return new EndpointRequest(
                "https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("charge.paid"));
    }
}
