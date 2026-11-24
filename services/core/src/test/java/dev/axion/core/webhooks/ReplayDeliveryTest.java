package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * {@link WebhooksApi#replay}, against a real PostgreSQL: which deliveries can be replayed, and how
 * often. Nothing is sent here; the queue's tests send what is queued.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, ReplayDeliveryTest.Clocked.class})
class ReplayDeliveryTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocked {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-24T12:00:00Z"));
        }
    }

    private final WebhooksApi webhooks;
    private final MutableClock clock;
    private final TestDeliveries deliveries;
    private final UuidV7Generator ids;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    ReplayDeliveryTest(WebhooksApi webhooks, MutableClock clock, DatabaseOwner owner) {
        this.webhooks = webhooks;
        this.clock = clock;
        this.deliveries = new TestDeliveries(owner.jdbc());
        this.ids = new UuidV7Generator(clock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SUCCEEDED", "DEAD", "CANCELED"})
    void queuesAReplayOfADeliveryInAnyStatusAndLeavesTheDeliveryAsItWas(String status) {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, status);
        int attempts = deliveries.attemptCount(delivery);
        Optional<Instant> next = deliveries.nextAttemptAt(delivery);

        ReplayResult result = replay(tenant, endpoint, delivery);

        assertThat(result).isInstanceOfSatisfying(ReplayResult.Queued.class, queued -> {
            assertThat(queued.delivery().id()).isEqualTo(new WebhookDeliveryId(delivery));
            assertThat(queued.delivery().status()).isEqualTo(DeliveryStatus.valueOf(status));
        });
        assertThat(deliveries.replays(delivery)).containsExactly("PENDING");
        assertThat(deliveries.status(delivery)).isEqualTo(status);
        assertThat(deliveries.attemptCount(delivery)).isEqualTo(attempts);
        assertThat(deliveries.nextAttemptAt(delivery)).isEqualTo(next);
    }

    @Test
    void findsNoUnknownDelivery() {
        WebhookEndpointId endpoint = create(tenant);

        assertThat(replay(tenant, endpoint, UUID.randomUUID())).isInstanceOf(ReplayResult.DeliveryNotFound.class);
    }

    @Test
    void findsNoDeliveryOfAnotherEndpoint() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(create(tenant), "DEAD");

        assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.DeliveryNotFound.class);
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void findsNoDeliveryOfAnotherTenant() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "DEAD");

        assertThat(replay(new TenantId(UUID.randomUUID()), endpoint, delivery))
                .isInstanceOf(ReplayResult.DeliveryNotFound.class);
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void findsNoDeliveryOfADeletedEndpoint() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "DEAD");
        as(tenant, () -> webhooks.delete(endpoint));

        assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.DeliveryNotFound.class);
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void refusesAReplayWhileTheEndpointIsDisabledAndQueuesItOnceEnabledAgain() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "CANCELED");
        change(endpoint, EndpointStatus.DISABLED);

        assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.EndpointDisabled.class);

        change(endpoint, EndpointStatus.ENABLED);

        assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.Queued.class);
        assertThat(deliveries.replays(delivery)).containsExactly("PENDING");
    }

    @Test
    void replaysADeliveryTenTimesADayAndAgainOnceTheFirstIsADayOld() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "DEAD");
        Instant first = clock.instant();
        for (int i = 0; i < WebhooksApi.MAX_REPLAYS; i++) {
            assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.Queued.class);
            clock.advance(Duration.ofMinutes(1));
        }

        assertThat(replay(tenant, endpoint, delivery))
                .isEqualTo(new ReplayResult.LimitReached(first.plus(WebhooksApi.REPLAY_WINDOW)));

        clock.advance(Duration.between(clock.instant(), first.plus(WebhooksApi.REPLAY_WINDOW)));

        assertThat(replay(tenant, endpoint, delivery)).isInstanceOf(ReplayResult.Queued.class);
        assertThat(deliveries.replays(delivery)).hasSize(WebhooksApi.MAX_REPLAYS + 1);
    }

    @Test
    void countsEachDeliverysReplaysOnTheirOwn() {
        WebhookEndpointId endpoint = create(tenant);
        UUID replayed = delivered(endpoint, "DEAD");
        UUID other = delivered(endpoint, "DEAD");
        for (int i = 0; i < WebhooksApi.MAX_REPLAYS; i++) {
            replay(tenant, endpoint, replayed);
        }

        assertThat(replay(tenant, endpoint, other)).isInstanceOf(ReplayResult.Queued.class);
    }

    @Test
    void queuesNoMoreThanTheLimitWhenReplaysRunConcurrently() throws Exception {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "DEAD");

        List<Future<ReplayResult>> results = new ArrayList<>();
        try (var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 2 * WebhooksApi.MAX_REPLAYS; i++) {
                results.add(callers.submit(() -> replay(tenant, endpoint, delivery)));
            }
        }

        List<ReplayResult> outcomes = new ArrayList<>();
        for (Future<ReplayResult> result : results) {
            outcomes.add(result.get());
        }
        assertThat(outcomes.stream().filter(ReplayResult.Queued.class::isInstance))
                .hasSize(WebhooksApi.MAX_REPLAYS);
        assertThat(outcomes.stream().filter(ReplayResult.LimitReached.class::isInstance))
                .hasSize(WebhooksApi.MAX_REPLAYS);
        assertThat(deliveries.replays(delivery)).hasSize(WebhooksApi.MAX_REPLAYS);
    }

    private ReplayResult replay(TenantId owner, WebhookEndpointId endpoint, UUID delivery) {
        return as(owner, () -> webhooks.replay(endpoint, new WebhookDeliveryId(delivery)));
    }

    private void change(WebhookEndpointId endpoint, EndpointStatus status) {
        var changes = new EndpointChanges(Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(status));
        as(tenant, () -> webhooks.update(endpoint, changes));
    }

    private WebhookEndpointId create(TenantId owner) {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("*"));
        var result = as(owner, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    private UUID delivered(WebhookEndpointId endpoint, String status) {
        clock.advance(Duration.ofMillis(1));
        return deliveries.logged(ids.next(), tenant, endpoint, "charge.paid", status);
    }

    private static <T> T as(TenantId owner, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(owner, Environment.TEST, work);
    }
}
