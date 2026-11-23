package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link WebhooksApi#create} under concurrent requests, against a real PostgreSQL. Each call runs on
 * its own thread, in its own transaction, so only the per-tenant lock keeps the count right.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
class CreateEndpointConcurrencyTest {

    private static final int REQUESTS = 2 * WebhooksApi.MAX_ENDPOINTS;

    private final WebhooksApi webhooks;
    private final JdbcClient owner;

    @Autowired
    CreateEndpointConcurrencyTest(WebhooksApi webhooks, DatabaseOwner owner) {
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
    }

    @Test
    void neverPassesTheLimitUnderConcurrentRequests() throws InterruptedException, ExecutionException {
        var tenant = new TenantId(UUID.randomUUID());
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("charge.paid"));

        var results = new ArrayList<CreateEndpointResult>();
        var ready = new CountDownLatch(REQUESTS);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(REQUESTS)) {
            var futures = new ArrayList<Future<CreateEndpointResult>>();
            for (int i = 0; i < REQUESTS; i++) {
                // A latch holds the threads until all of them are ready, so the calls start together.
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return TestCaller.call(tenant, Environment.TEST, () -> webhooks.create(request));
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results)
                .filteredOn(CreateEndpointResult.Created.class::isInstance)
                .hasSize(WebhooksApi.MAX_ENDPOINTS);
        assertThat(results)
                .filteredOn(new CreateEndpointResult.LimitReached(WebhooksApi.MAX_ENDPOINTS)::equals)
                .hasSize(REQUESTS - WebhooksApi.MAX_ENDPOINTS);
        assertThat(owner.sql("SELECT count(*) FROM webhooks.endpoints WHERE tenant_id = ?")
                        .param(tenant.value())
                        .query(Integer.class)
                        .single())
                .isEqualTo(WebhooksApi.MAX_ENDPOINTS);
    }
}
