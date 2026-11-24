package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link DeliveryLogRetention} against a real PostgreSQL (events and webhooks design, section 8.8).
 * The owner commits the rows, aged by the database's own time, since the job removes them in
 * transactions of its own; the scheduled run of the job may remove some first, so the tests check
 * what is left, not what a run counted.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DeliveryLogRetentionTest {

    private static final String EXPIRED = "now() - interval '31 days'";

    private static final String RETAINED = "now() - interval '29 days'";

    private final DeliveryLogRetention retention;
    private final JdbcClient owner;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    DeliveryLogRetentionTest(DeliveryLogRetention retention, DatabaseOwner owner) {
        this.retention = retention;
        this.owner = owner.jdbc();
    }

    @Test
    void removesAnEndedDeliveryWithItsAttemptsOnceItsRetentionEnds() {
        UUID endpoint = endpoint(null);
        UUID event = event(EXPIRED);
        UUID succeeded = delivery(endpoint, event, "SUCCEEDED", EXPIRED);
        attempt(succeeded, EXPIRED);
        UUID dead = delivery(endpoint, event(EXPIRED), "DEAD", EXPIRED);
        UUID canceled = delivery(endpoint, event(EXPIRED), "CANCELED", EXPIRED);

        retention.removeExpired();

        assertThat(exists("deliveries", succeeded)).isFalse();
        assertThat(exists("deliveries", dead)).isFalse();
        assertThat(exists("deliveries", canceled)).isFalse();
        assertThat(attempts(succeeded)).isZero();
        assertThat(exists("events", event)).isFalse();
    }

    @Test
    void keepsADeliveryWithinItsRetention() {
        UUID endpoint = endpoint(null);
        UUID young = delivery(endpoint, event(EXPIRED), "SUCCEEDED", RETAINED);
        UUID pending = delivery(endpoint, event(EXPIRED), "PENDING", EXPIRED);

        retention.removeExpired();

        assertThat(exists("deliveries", young)).isTrue();
        assertThat(exists("deliveries", pending)).isTrue();
    }

    @Test
    void keepsAnOldDeliveryWhoseLastAttemptIsWithinItsRetention() {
        UUID endpoint = endpoint(null);
        UUID delivery = delivery(endpoint, event(EXPIRED), "SUCCEEDED", EXPIRED);
        attempt(delivery, EXPIRED);
        attempt(delivery, RETAINED);

        retention.removeExpired();

        assertThat(exists("deliveries", delivery)).isTrue();
        assertThat(attempts(delivery)).isEqualTo(2);
    }

    @Test
    void removesAnExpiredDeliveryWithTheReplaysItEnded() {
        UUID endpoint = endpoint(null);
        UUID delivery = delivery(endpoint, event(EXPIRED), "DEAD", EXPIRED);
        attempt(delivery, EXPIRED);
        replay(delivery, "SENT", EXPIRED);
        replay(delivery, "CANCELED", EXPIRED);

        retention.removeExpired();

        assertThat(exists("deliveries", delivery)).isFalse();
        assertThat(replays(delivery)).isZero();
    }

    @ParameterizedTest
    @CsvSource({"SENT, RETAINED", "CANCELED, RETAINED", "PENDING, EXPIRED"})
    void keepsAnOldDeliveryWithAReplayWithinItsRetentionOrPending(String status, String requested) {
        UUID endpoint = endpoint(null);
        UUID delivery = delivery(endpoint, event(EXPIRED), "DEAD", EXPIRED);
        replay(delivery, status, requested.equals("RETAINED") ? RETAINED : EXPIRED);

        retention.removeExpired();

        assertThat(exists("deliveries", delivery)).isTrue();
        assertThat(replays(delivery)).isOne();
    }

    @Test
    void keepsAnEventWithinItsRetentionOrStillSent() {
        UUID endpoint = endpoint(null);
        UUID young = event(RETAINED);
        UUID sent = event(EXPIRED);
        delivery(endpoint, sent, "SUCCEEDED", RETAINED);

        retention.removeExpired();

        assertThat(exists("events", young)).isTrue();
        assertThat(exists("events", sent)).isTrue();
    }

    @Test
    void removesAnEventNoEndpointReceived() {
        UUID event = event(EXPIRED);

        retention.removeExpired();

        assertThat(exists("events", event)).isFalse();
    }

    @Test
    void removesADeletedEndpointWithItsSecretsOnceItsLogIsGone() {
        UUID endpoint = endpoint(EXPIRED);
        secret(endpoint);
        UUID delivery = delivery(endpoint, event(EXPIRED), "CANCELED", EXPIRED);

        retention.removeExpired();

        assertThat(exists("deliveries", delivery)).isFalse();
        assertThat(exists("endpoints", endpoint)).isFalse();
        assertThat(secrets(endpoint)).isZero();
    }

    @Test
    void keepsADeletedEndpointWhileItsLogIsKeptOrItWasDeletedRecently() {
        UUID withLog = endpoint(EXPIRED);
        delivery(withLog, event(EXPIRED), "CANCELED", RETAINED);
        UUID recent = endpoint(RETAINED);
        secret(recent);
        UUID live = endpoint(null);
        secret(live);

        retention.removeExpired();

        assertThat(exists("endpoints", withLog)).isTrue();
        assertThat(exists("endpoints", recent)).isTrue();
        assertThat(secrets(recent)).isOne();
        assertThat(exists("endpoints", live)).isTrue();
        assertThat(secrets(live)).isOne();
    }

    @Test
    void removesTheExpiredLogInAsManyBatchesAsItTakes() {
        UUID endpoint = endpoint(null);
        int expired = 2 * DeliveryLogRetention.BATCH_SIZE + 500;
        owner.sql("""
                        WITH made AS (
                            INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                            SELECT gen_random_uuid(), :tenant, 'charge.paid', '\\x7b7d', %1$s
                              FROM generate_series(1, :count)
                            RETURNING id)
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, created_at)
                        SELECT gen_random_uuid(), :tenant, :endpoint, id, 'SUCCEEDED', 1, %1$s FROM made
                        """.formatted(EXPIRED))
                .param("tenant", tenant)
                .param("endpoint", endpoint)
                .param("count", expired)
                .update();

        retention.removeExpired();

        assertThat(owner.sql("SELECT count(*) FROM webhooks.deliveries WHERE endpoint_id = ?")
                        .param(endpoint)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(owner.sql("SELECT count(*) FROM webhooks.events WHERE tenant_id = ?")
                        .param(tenant)
                        .query(Integer.class)
                        .single())
                .isZero();
    }

    // Deleted at the time, an SQL expression, or not deleted when null.
    private UUID endpoint(@Nullable String deletedAt) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, environment, url, enabled_events, status, created_at, updated_at,
                                deleted_at)
                        VALUES (?, ?, 'TEST', 'https://orders.example.com/axion', '{*}', 'ENABLED', now(), now(), %s)
                        """.formatted(deletedAt == null ? "NULL" : deletedAt))
                .params(id, tenant)
                .update();
        return id;
    }

    private void secret(UUID endpoint) {
        owner.sql("""
                        INSERT INTO webhooks.secrets (id, endpoint_id, tenant_id, ciphertext, created_at)
                        VALUES (gen_random_uuid(), ?, ?, '\\x00', now())
                        """).params(endpoint, tenant).update();
    }

    private UUID event(String occurredAt) {
        UUID id = UUID.randomUUID();
        owner.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', '\\x7b7d', %s)
                        """.formatted(occurredAt)).params(id, tenant).update();
        return id;
    }

    private UUID delivery(UUID endpoint, UUID event, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        boolean pending = status.equals("PENDING");
        owner.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at,
                                created_at)
                        VALUES (?, ?, ?, ?, ?, ?, %s, %s)
                        """.formatted(pending ? "'2999-01-01T00:00:00Z'" : "NULL", createdAt))
                .params(id, tenant, endpoint, event, status, pending ? 0 : 1)
                .update();
        return id;
    }

    private void replay(UUID delivery, String status, String requestedAt) {
        owner.sql("""
                        INSERT INTO webhooks.replays (id, tenant_id, delivery_id, status, requested_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, %s)
                        """.formatted(requestedAt)).params(tenant, delivery, status).update();
    }

    private int replays(UUID delivery) {
        return owner.sql("SELECT count(*) FROM webhooks.replays WHERE delivery_id = ?")
                .param(delivery)
                .query(Integer.class)
                .single();
    }

    private void attempt(UUID delivery, String startedAt) {
        owner.sql("""
                        INSERT INTO webhooks.attempts (id, tenant_id, delivery_id, started_at, duration_ms, status_code)
                        VALUES (gen_random_uuid(), ?, ?, %s, 5, 200)
                        """.formatted(startedAt)).params(tenant, delivery).update();
    }

    private boolean exists(String table, UUID id) {
        return owner.sql("SELECT EXISTS (SELECT 1 FROM webhooks." + table + " WHERE id = ?)")
                .param(id)
                .query(Boolean.class)
                .single();
    }

    private int attempts(UUID delivery) {
        return owner.sql("SELECT count(*) FROM webhooks.attempts WHERE delivery_id = ?")
                .param(delivery)
                .query(Integer.class)
                .single();
    }

    private int secrets(UUID endpoint) {
        return owner.sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint)
                .query(Integer.class)
                .single();
    }
}
