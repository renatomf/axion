package dev.finaxis.core.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.util.ArrayList;
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
 * {@link AccessApi#issue} under concurrent requests, against a real PostgreSQL. Each call runs on its
 * own thread, in its own transaction, so only the per-tenant lock keeps the count right.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IssueApiKeyConcurrencyTest {

    private static final int REQUESTS = 2 * AccessApi.MAX_ACTIVE_KEYS;

    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;

    @Autowired
    IssueApiKeyConcurrencyTest(AccessApi access, TenancyApi tenancy, DatabaseOwner databaseOwner) {
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = databaseOwner.jdbc();
    }

    @Test
    void neverPassesTheLimitUnderConcurrentRequests() throws InterruptedException, ExecutionException {
        var organization = tenancy.createOrganization("Acme", UUID.randomUUID().toString());
        var admin = AccessFixtures.admin(organization, Environment.TEST);

        var results = new ArrayList<IssueResult>();
        var ready = new CountDownLatch(REQUESTS);
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(REQUESTS)) {
            var futures = new ArrayList<Future<IssueResult>>();
            for (int i = 0; i < REQUESTS; i++) {
                var name = "Key " + i;
                // A latch holds the threads until all of them are ready, so the calls start together.
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return access.issue(admin, name, Set.of(Permission.CHARGES_READ));
                }));
            }
            ready.await();
            start.countDown();
            for (var future : futures) {
                results.add(future.get());
            }
        }

        assertThat(results).filteredOn(IssueResult.Issued.class::isInstance).hasSize(AccessApi.MAX_ACTIVE_KEYS);
        assertThat(results)
                .filteredOn(new IssueResult.LimitReached(AccessApi.MAX_ACTIVE_KEYS)::equals)
                .hasSize(REQUESTS - AccessApi.MAX_ACTIVE_KEYS);
        assertThat(keysOf(organization.test().value())).isEqualTo(AccessApi.MAX_ACTIVE_KEYS);
    }

    private int keysOf(UUID tenant) {
        return owner.sql("SELECT count(*) FROM access.api_keys WHERE tenant_id = ?")
                .param(tenant)
                .query(Integer.class)
                .single();
    }
}
