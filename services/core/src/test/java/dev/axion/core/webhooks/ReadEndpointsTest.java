package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * {@link WebhooksApi#endpoints} and {@link WebhooksApi#endpoint}, against a real PostgreSQL. Each
 * endpoint is created a millisecond after the previous one: UUIDv7 keys of one millisecond are not
 * ordered, so endpoints created within it would list in any order.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, ReadEndpointsTest.Clocked.class})
class ReadEndpointsTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Clocked {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-23T12:00:00Z"));
        }
    }

    private final WebhooksApi webhooks;
    private final MutableClock clock;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    ReadEndpointsTest(WebhooksApi webhooks, MutableClock clock) {
        this.webhooks = webhooks;
        this.clock = clock;
    }

    @Test
    void readsAnEndpointAsItWasRegistered() {
        EndpointView created = create("Order service");

        assertThat(as(tenant, () -> webhooks.endpoint(created.id()))).contains(created);
    }

    @Test
    void findsNoUnknownEndpoint() {
        assertThat(as(tenant, () -> webhooks.endpoint(new WebhookEndpointId(UUID.randomUUID()))))
                .isEmpty();
    }

    @Test
    void findsNoEndpointOfAnotherTenant() {
        EndpointView created = create("Order service");

        assertThat(as(new TenantId(UUID.randomUUID()), () -> webhooks.endpoint(created.id())))
                .isEmpty();
    }

    @Test
    void listsTheTenantsEndpointsNewestFirst() {
        EndpointView first = create("First");
        EndpointView second = create("Second");
        EndpointView third = create("Third");

        EndpointPage page = as(tenant, () -> webhooks.endpoints(EndpointCursor.first(), 10));

        assertThat(page.endpoints()).containsExactly(third, second, first);
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void pagesThroughTheEndpointsWithoutGapsOrRepeats() {
        List<EndpointView> created = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            created.addFirst(create("Endpoint " + i));
        }

        List<EndpointView> read = new ArrayList<>();
        EndpointCursor cursor = EndpointCursor.first();
        int pages = 0;
        while (true) {
            EndpointCursor current = cursor;
            EndpointPage page = as(tenant, () -> webhooks.endpoints(current, 2));
            read.addAll(page.endpoints());
            pages++;
            if (page.next().isEmpty()) {
                break;
            }
            cursor = page.next().get();
        }

        assertThat(read).isEqualTo(created);
        assertThat(pages).isEqualTo(3);
    }

    @Test
    void leavesTheLastFullPageWithoutANextCursor() {
        create("First");
        create("Second");

        assertThat(as(tenant, () -> webhooks.endpoints(EndpointCursor.first(), 2))
                        .next())
                .isEmpty();
    }

    @Test
    void listsNoEndpointOfAnotherTenant() {
        create("Order service");

        assertThat(as(new TenantId(UUID.randomUUID()), () -> webhooks.endpoints(EndpointCursor.first(), 10))
                        .endpoints())
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 101})
    void refusesAPageSizeOutsideItsLimits(int limit) {
        assertThatThrownBy(() -> as(tenant, () -> webhooks.endpoints(EndpointCursor.first(), limit)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void needsATenantBound() {
        assertThatThrownBy(() -> webhooks.endpoints(EndpointCursor.first(), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> webhooks.endpoint(new WebhookEndpointId(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    private EndpointView create(String description) {
        clock.advance(Duration.ofMillis(1));
        var request = new EndpointRequest(
                "https://orders.example.com/axion", Optional.of(description), Set.of("charge.paid", "charge.expired"));
        var result = as(tenant, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    private static <T> T as(TenantId tenant, ScopedValue.CallableOp<T, RuntimeException> work) {
        return TestCaller.call(tenant, Environment.TEST, work);
    }
}
