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
 * The replays table and the rules the database enforces on it (events and webhooks design, section
 * 8.7), checked against a real PostgreSQL. The owner arranges a delivery of each of two tenants,
 * then the module's role queues and reads replays; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class WebhookReplaysSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID delivery = UUID.randomUUID();
    private final UUID othersDelivery = UUID.randomUUID();

    @Autowired
    WebhookReplaysSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @BeforeEach
    void insertADeliveryOfEachTenant() {
        insertDelivery(tenant, delivery);
        insertDelivery(other, othersDelivery);
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SENT", "CANCELED"})
    void storesAReplayInEachStatus(String status) {
        insertReplay(tenant, delivery, status, null);
    }

    @Test
    void storesALeaseOnAPendingReplay() {
        insertReplay(tenant, delivery, "PENDING", "now()");
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertRejected(() -> insertReplay(tenant, delivery, "SENDING", null), "replays_status_check");
    }

    @Test
    void rejectsALeaseOnASentReplay() {
        assertRejected(() -> insertReplay(tenant, delivery, "SENT", "now()"), "replays_check");
    }

    @Test
    void rejectsAReplayOfNoDelivery() {
        assertRejected(() -> insertReplay(tenant, UUID.randomUUID(), "PENDING", null), "replays_delivery_id_fkey");
    }

    @Test
    void letsTheModuleRoleQueueAReplayOfItsTenantsDelivery() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        insertReplay(tenant, delivery, "PENDING", null);
    }

    @Test
    void deniesTheModuleRoleQueueingAReplayOfAnotherTenantsDelivery() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertReplay(tenant, othersDelivery, "PENDING", null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleQueueingAReplayForAnotherTenant() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertReplay(other, othersDelivery, "PENDING", null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleQueueingAReplayWithNoTenantBound() {
        DatabaseRole.WEBHOOKS.actAs(jdbc);

        assertThatThrownBy(() -> insertReplay(tenant, delivery, "PENDING", null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "CANCELED"})
    void deniesTheModuleRoleQueueingAReplayThatEnded(String status) {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertReplay(tenant, delivery, status, null))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleQueueingAReplayAlreadyHeld() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertReplay(tenant, delivery, "PENDING", "now()"))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void showsTheModuleRoleOnlyItsTenantsReplays() {
        insertReplay(tenant, delivery, "SENT", null);
        insertReplay(other, othersDelivery, "SENT", null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(jdbc.sql("SELECT delivery_id FROM webhooks.replays")
                        .query(UUID.class)
                        .list())
                .containsExactly(delivery);
    }

    @Test
    void deniesTheModuleRoleReadingALease() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("SELECT leased_until FROM webhooks.replays")
                        .query()
                        .listOfRows())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheModuleRoleCancelItsTenantsPendingReplay() {
        insertReplay(tenant, delivery, "PENDING", "now()");
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(setStatus("CANCELED", "leased_until = NULL")).isOne();
    }

    @Test
    void hidesAnotherTenantsReplayFromTheModuleRolesChanges() {
        insertReplay(other, othersDelivery, "PENDING", null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(setStatus("CANCELED", "leased_until = NULL")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "CANCELED"})
    void hidesAReplayThatEndedFromTheModuleRolesChanges(String status) {
        insertReplay(tenant, delivery, status, null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(setStatus("CANCELED", "leased_until = NULL")).isZero();
    }

    @Test
    void deniesTheModuleRoleMarkingAReplaySent() {
        insertReplay(tenant, delivery, "PENDING", null);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> setStatus("SENT", "leased_until = NULL"))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void letsTheJobsRoleCancelAPendingReplayOfAnyTenant() {
        insertReplay(other, othersDelivery, "PENDING", "now()");
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThat(setStatus("CANCELED", "leased_until = NULL")).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SENT", "CANCELED"})
    void hidesAReplayThatEndedFromTheJobsRolesChanges(String status) {
        insertReplay(tenant, delivery, status, null);
        DatabaseRole.WEBHOOKS_JOBS.actAs(jdbc);

        assertThat(setStatus("PENDING", "leased_until = NULL")).isZero();
    }

    private int setStatus(String status, String alsoSet) {
        return jdbc.sql("UPDATE webhooks.replays SET status = ?, " + alsoSet)
                .param(status)
                .update();
    }

    private void insertDelivery(UUID owner, UUID id) {
        UUID endpoint = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, environment, url, enabled_events, status, created_at, updated_at)
                        VALUES (?, ?, 'TEST', 'https://example.com/axion/webhooks', '{*}', 'ENABLED', now(), now())
                        """).params(endpoint, owner).update();
        jdbc.sql("""
                        INSERT INTO webhooks.events (id, tenant_id, type, body, occurred_at)
                        VALUES (?, ?, 'charge.paid', '\\x7b7d', now())
                        """).params(event, owner).update();
        jdbc.sql("""
                        INSERT INTO webhooks.deliveries
                               (id, tenant_id, endpoint_id, event_id, status, attempt_count, next_attempt_at, created_at)
                        VALUES (?, ?, ?, ?, 'DEAD', 9, NULL, now())
                        """).params(id, owner, endpoint, event).update();
    }

    private void insertReplay(UUID owner, UUID of, String status, @Nullable String leasedUntil) {
        jdbc.sql("""
                        INSERT INTO webhooks.replays (id, tenant_id, delivery_id, status, requested_at, leased_until)
                        VALUES (gen_random_uuid(), ?, ?, ?, now(), %s)
                        """.formatted(leasedUntil == null ? "NULL" : leasedUntil))
                .params(owner, of, status)
                .update();
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
