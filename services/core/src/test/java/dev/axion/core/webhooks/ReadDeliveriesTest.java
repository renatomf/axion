package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.AttemptFailure;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
 * {@link WebhooksApi#deliveries} and {@link WebhooksApi#delivery}, against a real PostgreSQL. The
 * owner arranges the deliveries and their attempts, each delivery a millisecond after the previous
 * one, so their UUIDv7 keys are in the order they were made.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, ReadDeliveriesTest.Clocked.class})
class ReadDeliveriesTest {

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
    ReadDeliveriesTest(WebhooksApi webhooks, MutableClock clock, DatabaseOwner owner) {
        this.webhooks = webhooks;
        this.clock = clock;
        this.deliveries = new TestDeliveries(owner.jdbc());
        this.ids = new UuidV7Generator(clock);
    }

    @Test
    void readsADeliveryWithItsAttemptsOldestFirst() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "charge.paid", "SUCCEEDED");
        Instant first = Instant.parse("2026-11-24T12:00:00Z");
        deliveries.attempt(delivery, tenant, first.plusSeconds(60), 87, 200, null, "ok");
        deliveries.attempt(delivery, tenant, first, 10_000, null, "TIMEOUT", null);

        DeliveryDetail detail = as(tenant, () -> webhooks.delivery(endpoint, new WebhookDeliveryId(delivery)))
                .orElseThrow();

        assertThat(detail.delivery().id()).isEqualTo(new WebhookDeliveryId(delivery));
        assertThat(detail.delivery().endpoint()).isEqualTo(endpoint);
        assertThat(detail.delivery().eventType()).isEqualTo("charge.paid");
        assertThat(detail.delivery().status()).isEqualTo(DeliveryStatus.SUCCEEDED);
        assertThat(detail.delivery().nextAttemptAt()).isEmpty();
        assertThat(detail.attempts())
                .extracting(
                        AttemptView::startedAt,
                        AttemptView::duration,
                        AttemptView::outcome,
                        AttemptView::responseExcerpt)
                .containsExactly(
                        tuple(
                                first,
                                Duration.ofSeconds(10),
                                new Attempt.Failed(AttemptFailure.TIMEOUT),
                                Optional.empty()),
                        tuple(
                                first.plusSeconds(60),
                                Duration.ofMillis(87),
                                new Attempt.Answered(200),
                                Optional.of("ok")));
    }

    @Test
    void readsAPendingDeliveryWithItsNextAttempt() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "charge.paid", "PENDING");

        DeliveryDetail detail = as(tenant, () -> webhooks.delivery(endpoint, new WebhookDeliveryId(delivery)))
                .orElseThrow();

        assertThat(detail.delivery().status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(detail.delivery().nextAttemptAt()).contains(Instant.parse("2999-01-01T00:00:00Z"));
        assertThat(detail.attempts()).isEmpty();
    }

    @Test
    void findsNoDeliveryOfAnotherEndpoint() {
        WebhookEndpointId endpoint = create(tenant);
        WebhookEndpointId other = create(tenant);
        UUID delivery = delivered(other, "charge.paid", "SUCCEEDED");

        assertThat(as(tenant, () -> webhooks.delivery(endpoint, new WebhookDeliveryId(delivery))))
                .isEmpty();
    }

    @Test
    void findsNoDeliveryOfAnotherTenant() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "charge.paid", "SUCCEEDED");

        assertThat(as(
                        new TenantId(UUID.randomUUID()),
                        () -> webhooks.delivery(endpoint, new WebhookDeliveryId(delivery))))
                .isEmpty();
    }

    @Test
    void listsAnEndpointsDeliveriesNewestFirst() {
        WebhookEndpointId endpoint = create(tenant);
        UUID first = delivered(endpoint, "charge.created", "SUCCEEDED");
        UUID second = delivered(endpoint, "charge.paid", "DEAD");
        UUID third = delivered(endpoint, "charge.paid", "PENDING");
        delivered(create(tenant), "charge.paid", "SUCCEEDED");

        DeliveryPage page = list(tenant, endpoint, DeliveryFilter.all(), DeliveryCursor.first(), 10);

        assertThat(page.deliveries())
                .extracting(DeliveryView::id)
                .containsExactly(
                        new WebhookDeliveryId(third), new WebhookDeliveryId(second), new WebhookDeliveryId(first));
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void filtersTheDeliveriesByStatusAndEventType() {
        WebhookEndpointId endpoint = create(tenant);
        UUID createdAndDead = delivered(endpoint, "charge.created", "DEAD");
        UUID paidAndDead = delivered(endpoint, "charge.paid", "DEAD");
        UUID paid = delivered(endpoint, "charge.paid", "SUCCEEDED");

        assertThat(ids(list(tenant, endpoint, filter(DeliveryStatus.DEAD, null), DeliveryCursor.first(), 10)))
                .containsExactly(paidAndDead, createdAndDead);
        assertThat(ids(list(tenant, endpoint, filter(null, "charge.paid"), DeliveryCursor.first(), 10)))
                .containsExactly(paid, paidAndDead);
        assertThat(ids(list(tenant, endpoint, filter(DeliveryStatus.DEAD, "charge.paid"), DeliveryCursor.first(), 10)))
                .containsExactly(paidAndDead);
        assertThat(ids(list(tenant, endpoint, filter(DeliveryStatus.CANCELED, null), DeliveryCursor.first(), 10)))
                .isEmpty();
    }

    @Test
    void pagesThroughTheDeliveriesWithoutGapsOrRepeats() {
        WebhookEndpointId endpoint = create(tenant);
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            made.addFirst(delivered(endpoint, "charge.paid", "SUCCEEDED"));
        }

        List<UUID> read = new ArrayList<>();
        DeliveryCursor cursor = DeliveryCursor.first();
        int pages = 0;
        while (true) {
            DeliveryPage page = list(tenant, endpoint, DeliveryFilter.all(), cursor, 2);
            read.addAll(ids(page));
            pages++;
            if (page.next().isEmpty()) {
                break;
            }
            cursor = page.next().get();
        }

        assertThat(read).isEqualTo(made);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void keepsTheLogOfADeletedEndpointReadable() {
        WebhookEndpointId endpoint = create(tenant);
        UUID delivery = delivered(endpoint, "charge.paid", "SUCCEEDED");
        as(tenant, () -> webhooks.delete(endpoint));

        assertThat(ids(list(tenant, endpoint, DeliveryFilter.all(), DeliveryCursor.first(), 10)))
                .containsExactly(delivery);
        assertThat(as(tenant, () -> webhooks.delivery(endpoint, new WebhookDeliveryId(delivery))))
                .isPresent();
    }

    @Test
    void findsNoLogOfAnUnknownEndpoint() {
        assertThat(as(
                        tenant,
                        () -> webhooks.deliveries(
                                new WebhookEndpointId(UUID.randomUUID()),
                                DeliveryFilter.all(),
                                DeliveryCursor.first(),
                                10)))
                .isEmpty();
    }

    @Test
    void findsNoLogOfAnotherTenantsEndpoint() {
        WebhookEndpointId endpoint = create(tenant);
        delivered(endpoint, "charge.paid", "SUCCEEDED");

        assertThat(as(
                        new TenantId(UUID.randomUUID()),
                        () -> webhooks.deliveries(endpoint, DeliveryFilter.all(), DeliveryCursor.first(), 10)))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void refusesAPageSizeOutsideItsLimits(int limit) {
        WebhookEndpointId endpoint = create(tenant);

        assertThatThrownBy(() -> list(tenant, endpoint, DeliveryFilter.all(), DeliveryCursor.first(), limit))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsATenantBound() {
        var endpoint = new WebhookEndpointId(UUID.randomUUID());

        assertThatThrownBy(() -> webhooks.deliveries(endpoint, DeliveryFilter.all(), DeliveryCursor.first(), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> webhooks.delivery(endpoint, new WebhookDeliveryId(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    private WebhookEndpointId create(TenantId owner) {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("*"));
        var result = as(owner, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    private UUID delivered(WebhookEndpointId endpoint, String type, String status) {
        clock.advance(Duration.ofMillis(1));
        return deliveries.logged(ids.next(), tenant, endpoint, type, status);
    }

    private DeliveryPage list(
            TenantId owner, WebhookEndpointId endpoint, DeliveryFilter filter, DeliveryCursor cursor, int limit) {
        return as(owner, () -> webhooks.deliveries(endpoint, filter, cursor, limit))
                .orElseThrow();
    }

    private static DeliveryFilter filter(@Nullable DeliveryStatus status, @Nullable String type) {
        return new DeliveryFilter(Optional.ofNullable(status), Optional.ofNullable(type));
    }

    private static List<UUID> ids(DeliveryPage page) {
        return page.deliveries().stream().map(delivery -> delivery.id().value()).toList();
    }

    private static <T> T as(TenantId owner, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(owner, Environment.TEST, work);
    }
}
