package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The events and deliveries tables and the rules the database enforces on them (events and webhooks
 * design, section 11), checked against a real PostgreSQL. The owner arranges the rows, then the
 * module's role reads and writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class WebhookDeliveriesSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID endpoint = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @Autowired
    WebhookDeliveriesSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @BeforeEach
    void insertAnEndpointAndAnEvent() {
        jdbc.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, environment, url, enabled_events, status, created_at, updated_at)
                        VALUES (?, ?, 'TEST', 'https://example.com/axion/webhooks', '{*}', 'ENABLED', now(), now())
                        """).params(endpoint, tenant).update();
        insertEvent(event);
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAPendingDelivery() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "DEAD", "CANCELED"})
    void storesAFinishedDeliveryWithNoNextAttempt(String status) {
        insertDelivery(endpoint, event, status, 1, null, null);
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertRejected(() -> insertDelivery(endpoint, event, "SENDING", 0, null, null), "deliveries_status_check");
    }

    @Test
    void rejectsAPendingDeliveryWithNoNextAttempt() {
        assertRejected(() -> insertDelivery(endpoint, event, "PENDING", 0, null, null), "deliveries_check");
    }

    @Test
    void rejectsAFinishedDeliveryWithANextAttempt() {
        assertRejected(() -> insertDelivery(endpoint, event, "SUCCEEDED", 1, "now()", null), "deliveries_check");
    }

    @Test
    void rejectsALeaseOnAFinishedDelivery() {
        assertRejected(() -> insertDelivery(endpoint, event, "DEAD", 1, null, "now()"), "deliveries_check1");
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10})
    void rejectsAnAttemptCountOutsideZeroToNine(int count) {
        assertRejected(
                () -> insertDelivery(endpoint, event, "PENDING", count, "now()", null),
                "deliveries_attempt_count_check");
    }

    @Test
    void rejectsASecondDeliveryOfAnEventToAnEndpoint() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);

        assertRejected(
                () -> insertDelivery(endpoint, event, "PENDING", 0, "now()", null),
                "deliveries_endpoint_id_event_id_key");
    }

    @Test
    void rejectsADeliveryOfNoEvent() {
        assertRejected(
                () -> insertDelivery(endpoint, UUID.randomUUID(), "PENDING", 0, "now()", null),
                "deliveries_event_id_fkey");
    }

    @Test
    void rejectsADeliveryToNoEndpoint() {
        assertRejected(
                () -> insertDelivery(UUID.randomUUID(), event, "PENDING", 0, "now()", null),
                "deliveries_endpoint_id_fkey");
    }

    @Test
    void rejectsASecondEventWithTheSameId() {
        assertRejected(() -> insertEvent(event), "events_pkey");
    }

    @Test
    void letsTheModuleRoleRecordAnEventAndItsDelivery() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));
        UUID recorded = UUID.randomUUID();

        insertEvent(recorded, tenant);
        insertDelivery(endpoint, recorded, "PENDING", 0, "now()", null);
    }

    @Test
    void letsTheModuleRoleFindAnEventRecordedAlready() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int inserted = jdbc.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', '\\x7b7d', now())
                        ON CONFLICT (id) DO NOTHING
                        """).params(event, tenant).update();

        assertThat(inserted).isZero();
    }

    @Test
    void deniesTheModuleRoleReadingAnEventsBody() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("SELECT body FROM webhooks.events")
                        .query(byte[].class)
                        .list())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleRecordingAnEventForAnotherTenant() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertEvent(UUID.randomUUID(), UUID.randomUUID()))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleRecordingADeliveryWithNoTenantBound() {
        DatabaseRole.WEBHOOKS.actAs(jdbc);

        assertThatThrownBy(() -> insertDelivery(endpoint, event, "PENDING", 0, "now()", null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void letsTheModuleRoleCancelAPendingDelivery() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", "now()");
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int canceled = jdbc.sql("""
                        UPDATE webhooks.deliveries
                           SET status = 'CANCELED', next_attempt_at = NULL, leased_until = NULL
                         WHERE endpoint_id = ? AND status = 'PENDING'
                        """).param(endpoint).update();

        assertThat(canceled).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt_count = 9", "event_id = gen_random_uuid()", "tenant_id = gen_random_uuid()"})
    void deniesTheModuleRoleChangingADeliveryBeyondItsCancellation(String assignment) {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE webhooks.deliveries SET " + assignment + " WHERE endpoint_id = ?")
                        .param(endpoint)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void hidesAnotherTenantsDeliveriesFromTheModuleRole() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(UUID.randomUUID()));

        int canceled = jdbc.sql("UPDATE webhooks.deliveries SET status = 'CANCELED', next_attempt_at = NULL")
                .update();

        assertThat(canceled).isZero();
    }

    @Test
    void letsTheJobsRoleReadWhatADeliverySends() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThat(jdbc.sql("""
                        SELECT convert_from(e.body, 'UTF8') FROM webhooks.deliveries d
                          JOIN webhooks.events e ON e.id = d.event_id
                         WHERE d.endpoint_id = ? AND d.status = 'PENDING'
                        """).param(endpoint).query(String.class).single()).isEqualTo("{}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "DEAD"})
    void letsTheJobsRoleFinishAPendingDelivery(String status) {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", "now()");
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        int finished = jdbc.sql("""
                        UPDATE webhooks.deliveries
                           SET status = ?, attempt_count = attempt_count + 1, next_attempt_at = NULL,
                               leased_until = NULL
                         WHERE endpoint_id = ?
                        """).params(status, endpoint).update();

        assertThat(finished).isOne();
    }

    @Test
    void deniesTheJobsRoleCancellingADelivery() {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("""
                                UPDATE webhooks.deliveries SET status = 'CANCELED', next_attempt_at = NULL
                                 WHERE endpoint_id = ?
                                """).param(endpoint).update())
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheJobsRoleChangingAFinishedDelivery() {
        insertDelivery(endpoint, event, "SUCCEEDED", 1, null, null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        int changed = jdbc.sql("""
                        UPDATE webhooks.deliveries SET status = 'PENDING', next_attempt_at = now()
                         WHERE endpoint_id = ?
                        """).param(endpoint).update();

        assertThat(changed).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "INSERT INTO webhooks.deliveries (id, tenant_id, endpoint_id, event_id, status, attempt_count,"
                        + " next_attempt_at, created_at) SELECT gen_random_uuid(), tenant_id, endpoint_id, event_id,"
                        + " 'PENDING', 0, now(), now() FROM webhooks.deliveries",
                "DELETE FROM webhooks.deliveries",
                "UPDATE webhooks.deliveries SET endpoint_id = gen_random_uuid()",
                "UPDATE webhooks.events SET body = '\\x00'"
            })
    void deniesTheJobsRoleWhatSendingDoesNotNeed(String statement) {
        insertDelivery(endpoint, event, "PENDING", 0, "now()", null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql(statement).update()).rootCause().hasMessageContaining("permission denied");
    }

    private void insertEvent(UUID id) {
        insertEvent(id, tenant);
    }

    private void insertEvent(UUID id, UUID owner) {
        jdbc.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', '\\x7b7d', now())
                        """).params(id, owner).update();
    }

    // The times are SQL expressions, such as now(), or null.
    private void insertDelivery(
            UUID to,
            UUID of,
            String status,
            int attempts,
            @Nullable String nextAttemptAt,
            @Nullable String leasedUntil) {
        jdbc.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at,
                                leased_until, created_at)
                        VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, %s, %s, now())
                        """.formatted(sql(nextAttemptAt), sql(leasedUntil)))
                .params(tenant, to, of, status, attempts)
                .update();
    }

    private static String sql(@Nullable String expression) {
        return expression == null ? "NULL" : expression;
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
