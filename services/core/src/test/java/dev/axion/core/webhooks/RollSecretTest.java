package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link WebhooksApi#rollSecret} (events and webhooks design, section 6.2), against a real PostgreSQL. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, CommittedEvents.class})
class RollSecretTest {

    private static final Duration GRACE = Duration.ofHours(1);

    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final CommittedEvents events;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    RollSecretTest(WebhooksApi webhooks, DatabaseOwner owner, CommittedEvents events) {
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.events = events;
    }

    @Test
    void issuesANewSecret() {
        CreatedEndpoint created = create();

        RolledSecret rolled = rolled(roll(created.endpoint().id(), GRACE));

        assertThat(rolled.endpoint()).isEqualTo(created.endpoint());
        assertThat(rolled.secret()).startsWith("whsec_").isNotEqualTo(created.secret());
    }

    @Test
    void keepsThePreviousSecretForTheGracePeriod() {
        CreatedEndpoint created = create();
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        RolledSecret rolled = rolled(roll(created.endpoint().id(), GRACE));

        assertThat(rolled.previousSecretExpiresAt())
                .isBetween(before.plus(GRACE), Instant.now().plus(GRACE));
        assertThat(endings(created.endpoint().id()))
                .containsExactlyInAnyOrder(Optional.of(rolled.previousSecretExpiresAt()), Optional.empty());
    }

    @Test
    void endsThePreviousSecretAtOnceWithNoGracePeriod() {
        CreatedEndpoint created = create();

        RolledSecret rolled = rolled(roll(created.endpoint().id(), Duration.ZERO));

        assertThat(rolled.previousSecretExpiresAt()).isBeforeOrEqualTo(Instant.now());
        assertThat(rolled(roll(created.endpoint().id(), GRACE)).secret()).isNotEqualTo(rolled.secret());
    }

    @Test
    void refusesToRollAgainDuringTheGracePeriod() {
        CreatedEndpoint created = create();
        RolledSecret rolled = rolled(roll(created.endpoint().id(), GRACE));

        assertThat(roll(created.endpoint().id(), GRACE))
                .isEqualTo(new RollSecretResult.GracePeriodActive(rolled.previousSecretExpiresAt()));
        assertThat(endings(created.endpoint().id())).hasSize(2);
        assertThat(events.of(tenant, WebhookSecretRolled.class)).hasSize(1);
    }

    @Test
    void rollsAgainOnceTheGracePeriodEnds() {
        CreatedEndpoint created = create();
        rolled(roll(created.endpoint().id(), GRACE));
        owner.sql(
                        "UPDATE webhooks.secrets SET expires_at = created_at WHERE endpoint_id = ? AND expires_at IS NOT NULL")
                .param(created.endpoint().id().value())
                .update();

        assertThat(roll(created.endpoint().id(), GRACE)).isInstanceOf(RollSecretResult.Rolled.class);
    }

    @Test
    void rollsTheSecretOfADisabledEndpoint() {
        CreatedEndpoint created = create();
        var disabling = new EndpointChanges(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(EndpointStatus.DISABLED));
        as(tenant, () -> webhooks.update(created.endpoint().id(), disabling));

        assertThat(roll(created.endpoint().id(), GRACE)).isInstanceOf(RollSecretResult.Rolled.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 604_800})
    void acceptsAGracePeriodFromZeroToSevenDays(long seconds) {
        CreatedEndpoint created = create();

        assertThat(roll(created.endpoint().id(), Duration.ofSeconds(seconds)))
                .isInstanceOf(RollSecretResult.Rolled.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 604_801})
    void refusesAGracePeriodOutsideZeroToSevenDays(long seconds) {
        CreatedEndpoint created = create();

        assertThatThrownBy(() -> roll(created.endpoint().id(), Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(endings(created.endpoint().id())).containsExactly(Optional.empty());
    }

    @Test
    void findsNoUnknownEndpoint() {
        assertThat(roll(new WebhookEndpointId(UUID.randomUUID()), GRACE))
                .isEqualTo(new RollSecretResult.EndpointNotFound());
        assertThat(events.publications(tenant)).isEmpty();
    }

    @Test
    void findsNoDeletedEndpoint() {
        CreatedEndpoint created = create();
        as(tenant, () -> webhooks.delete(created.endpoint().id()));

        assertThat(roll(created.endpoint().id(), GRACE)).isEqualTo(new RollSecretResult.EndpointNotFound());
        assertThat(endings(created.endpoint().id())).containsExactly(Optional.empty());
    }

    @Test
    void leavesAnotherTenantsSecretInPlace() {
        var other = new TenantId(UUID.randomUUID());
        CreatedEndpoint others = as(other, () -> created(webhooks.create(request())));

        assertThat(roll(others.endpoint().id(), GRACE)).isEqualTo(new RollSecretResult.EndpointNotFound());
        assertThat(endings(others.endpoint().id())).containsExactly(Optional.empty());
    }

    @Test
    void publishesTheRollWithoutTheSecret() {
        CreatedEndpoint created = create();

        RolledSecret rolled = rolled(roll(created.endpoint().id(), GRACE));

        assertThat(events.of(tenant, WebhookSecretRolled.class)).singleElement().satisfies(event -> {
            assertThat(event.endpoint()).isEqualTo(created.endpoint());
            assertThat(event.previousSecretExpiresAt()).isEqualTo(rolled.previousSecretExpiresAt());
            assertThat(event.header().tenant()).isEqualTo(tenant);
            assertThat(event.header().actor()).isEqualTo(TestCaller.ACTOR);
            assertThat(event.header().correlationId()).isEqualTo(TestCaller.CORRELATION_ID);
        });
        assertThat(owner.sql("SELECT serialized_event FROM events.event_publication WHERE event_type = ?")
                        .param(WebhookSecretRolled.class.getName())
                        .query(String.class)
                        .list())
                .noneMatch(serialized -> serialized.contains(rolled.secret().substring("whsec_".length())));
    }

    @Test
    void rollsOnceUnderConcurrentRequests() throws InterruptedException, ExecutionException {
        CreatedEndpoint created = create();
        int requests = 8;

        var results = new ArrayList<RollSecretResult>();
        var ready = new CountDownLatch(requests);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(requests)) {
            var futures = new ArrayList<Future<RollSecretResult>>();
            for (int i = 0; i < requests; i++) {
                // A latch holds the threads until all of them are ready, so the calls start together.
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return roll(created.endpoint().id(), GRACE);
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results)
                .filteredOn(RollSecretResult.Rolled.class::isInstance)
                .hasSize(1);
        assertThat(results)
                .filteredOn(RollSecretResult.GracePeriodActive.class::isInstance)
                .hasSize(requests - 1);
        assertThat(endings(created.endpoint().id())).hasSize(2);
    }

    @Test
    void needsATenantBound() {
        assertThatThrownBy(() -> webhooks.rollSecret(new WebhookEndpointId(UUID.randomUUID()), GRACE))
                .isInstanceOf(IllegalStateException.class);
    }

    private CreatedEndpoint create() {
        return as(tenant, () -> created(webhooks.create(request())));
    }

    private RollSecretResult roll(WebhookEndpointId id, Duration grace) {
        return as(tenant, () -> webhooks.rollSecret(id, grace));
    }

    /** When each of the endpoint's secrets ends; empty for the current one. */
    private List<Optional<Instant>> endings(WebhookEndpointId endpoint) {
        return owner.sql("SELECT expires_at FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint.value())
                .query((row, rowNumber) -> Optional.ofNullable(row.getObject("expires_at", OffsetDateTime.class))
                        .map(OffsetDateTime::toInstant))
                .list();
    }

    private static <T> T as(TenantId caller, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(caller, Environment.TEST, work);
    }

    private static CreatedEndpoint created(CreateEndpointResult result) {
        assertThat(result).isInstanceOf(CreateEndpointResult.Created.class);
        return ((CreateEndpointResult.Created) result).endpoint();
    }

    private static RolledSecret rolled(RollSecretResult result) {
        assertThat(result).isInstanceOf(RollSecretResult.Rolled.class);
        return ((RollSecretResult.Rolled) result).secret();
    }

    private static EndpointRequest request() {
        return new EndpointRequest(
                "https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("charge.paid"));
    }
}
