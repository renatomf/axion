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
 * The attempts table and the rules the database enforces on it (events and webhooks design,
 * sections 8.7 and 11), checked against a real PostgreSQL. The owner arranges a delivery, then the
 * roles record, read and change its attempts; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class WebhookAttemptsSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();

    @Autowired
    WebhookAttemptsSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @BeforeEach
    void insertADelivery() {
        UUID endpoint = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, environment, url, enabled_events, status, created_at, updated_at)
                        VALUES (?, ?, 'TEST', 'https://example.com/axion/webhooks', '{*}', 'ENABLED', now(), now())
                        """).params(endpoint, tenant).update();
        jdbc.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', '\\x7b7d', now())
                        """).params(event, tenant).update();
        jdbc.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, 'PENDING', 0, now(), now())
                        """).params(delivery, tenant, endpoint, event).update();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAnAnswerWithTheStartOfItsBody() {
        insertAttempt(tenant, 200, null, "ok");
    }

    @Test
    void storesAFailure() {
        insertAttempt(tenant, null, "TIMEOUT", null);
    }

    @Test
    void rejectsAnAttemptWithBothAStatusCodeAndAFailure() {
        assertRejected(() -> insertAttempt(tenant, 500, "TIMEOUT", null), "attempts_outcome_check");
    }

    @Test
    void rejectsAnAttemptWithNeitherAStatusCodeNorAFailure() {
        assertRejected(() -> insertAttempt(tenant, null, null, null), "attempts_outcome_check");
    }

    @Test
    void rejectsAResponseOfAFailure() {
        assertRejected(() -> insertAttempt(tenant, null, "REDIRECT", "Moved"), "attempts_excerpt_check");
    }

    @Test
    void rejectsMoreThanAKibibyteOfResponse() {
        insertAttempt(tenant, 500, null, "a".repeat(1_024));

        assertRejected(() -> insertAttempt(tenant, 500, null, "a".repeat(1_025)), "attempts_response_excerpt_check");
    }

    @Test
    void rejectsAnUnknownFailure() {
        assertRejected(() -> insertAttempt(tenant, null, "LOST", null), "attempts_failure_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 600})
    void rejectsAStatusCodeOutsideHttps(int code) {
        assertRejected(() -> insertAttempt(tenant, code, null, null), "attempts_status_code_check");
    }

    @Test
    void rejectsAnAttemptOfNoDelivery() {
        assertRejected(() -> jdbc.sql("""
                                INSERT INTO webhooks.attempts
                                       (id, tenant_id, delivery_id, started_at, duration_ms, status_code)
                                VALUES (gen_random_uuid(), ?, gen_random_uuid(), now(), 5, 200)
                                """).param(tenant).update(), "attempts_delivery_id_fkey");
    }

    @Test
    void letsTheJobsRoleRecordAnAttemptInItsDeliverysTenant() {
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        insertAttempt(tenant, 204, null, null);
    }

    @Test
    void deniesTheJobsRoleRecordingAnAttemptInAnotherTenant() {
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> insertAttempt(UUID.randomUUID(), 204, null, null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheJobsRoleReadingTheLog() {
        insertAttempt(tenant, 204, null, null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("SELECT response_excerpt FROM webhooks.attempts")
                        .query(String.class)
                        .list())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheModuleRoleReadItsTenantsLog() {
        insertAttempt(tenant, 503, null, "down");
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(jdbc.sql("SELECT response_excerpt FROM webhooks.attempts WHERE delivery_id = ?")
                        .param(delivery)
                        .query(String.class)
                        .list())
                .containsExactly("down");
    }

    @Test
    void hidesAnotherTenantsLogFromTheModuleRole() {
        insertAttempt(tenant, 503, null, "down");
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(UUID.randomUUID()));

        assertThat(countAttempts()).isZero();
    }

    @Test
    void hidesTheLogFromTheModuleRoleWithNoTenantBound() {
        insertAttempt(tenant, 503, null, "down");
        DatabaseRole.WEBHOOKS.actAs(jdbc);

        assertThat(countAttempts()).isZero();
    }

    @Test
    void deniesEveryRoleUpdatingAnAttempt() {
        insertAttempt(tenant, 500, null, null);

        for (DatabaseRole role : new DatabaseRole[] {DatabaseRole.WEBHOOKS, DatabaseRole.WEBHOOKS_JOBS}) {
            jdbc.sql("SAVEPOINT change").update();
            role.actAs(jdbc, new TenantId(tenant));
            assertThatThrownBy(() -> jdbc.sql("UPDATE webhooks.attempts SET status_code = 200")
                            .update())
                    .as(role.name())
                    .rootCause()
                    .hasMessageContaining("permission denied");
            jdbc.sql("ROLLBACK TO SAVEPOINT change").update();
        }
    }

    @Test
    void deniesTheModuleRoleRemovingAnAttempt() {
        insertAttempt(tenant, 500, null, null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM webhooks.attempts").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheJobsRoleRemoveOnlyAnAttemptPastItsRetention() {
        insertAttempt(tenant, 500, null, null);
        jdbc.sql("""
                        INSERT INTO webhooks.attempts (id, tenant_id, delivery_id, started_at, duration_ms, status_code)
                        VALUES (gen_random_uuid(), ?, ?, now() - interval '31 days', 5, 500)
                        """).params(tenant, delivery).update();
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        int removed = jdbc.sql("DELETE FROM webhooks.attempts WHERE delivery_id = ?")
                .param(delivery)
                .update();

        assertThat(removed).isOne();
        assertThat(countAttempts()).isOne();
    }

    private int countAttempts() {
        return jdbc.sql("SELECT count(*) FROM webhooks.attempts WHERE delivery_id = ?")
                .param(delivery)
                .query(Integer.class)
                .single();
    }

    private void insertAttempt(
            UUID owner, @Nullable Integer statusCode, @Nullable String failure, @Nullable String excerpt) {
        jdbc.sql("""
                        INSERT INTO webhooks.attempts
                               (id, tenant_id, delivery_id, started_at, duration_ms, status_code, failure,
                                response_excerpt)
                        VALUES (gen_random_uuid(), :tenant, :delivery, now(), 5, :statusCode, :failure, :excerpt)
                        """)
                .param("tenant", owner)
                .param("delivery", delivery)
                .param("statusCode", statusCode)
                .param("failure", failure)
                .param("excerpt", excerpt)
                .update();
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
