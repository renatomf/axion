package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The endpoints and secrets tables and the rules the database enforces on them (events and webhooks
 * design, section 11), checked against a real PostgreSQL. The owner arranges the rows, then the
 * module's role reads and writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class WebhookEndpointsSchemaTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    WebhookEndpointsSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAnEndpointAndItsSecret() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);

        assertThat(jdbc.sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ?")
                        .param(row.id)
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/hooks", "ftp://example.com/hooks", "example.com"})
    void rejectsAUrlThatIsNotHttps(String url) {
        assertRejected(endpoint().url(url), "endpoints_url_check");
    }

    @Test
    void rejectsAUrlLongerThan2048Characters() {
        assertRejected(endpoint().url("https://example.com/" + "a".repeat(2_029)), "endpoints_url_check");
    }

    @Test
    void acceptsAUrlOf2048Characters() {
        insert(endpoint().url("https://example.com/" + "a".repeat(2_028)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 141})
    void rejectsADescriptionOutsideOneTo140Characters(int length) {
        assertRejected(endpoint().description("d".repeat(length)), "endpoints_description_check");
    }

    @Test
    void rejectsNoEventTypes() {
        assertRejected(endpoint().events(), "endpoints_enabled_events_check");
    }

    @Test
    void rejectsMoreThan20EventTypes() {
        String[] types = new String[21];
        for (int i = 0; i < types.length; i++) {
            types[i] = "type." + i;
        }
        assertRejected(endpoint().events(types), "endpoints_enabled_events_check");
    }

    @Test
    void rejectsANullEventType() {
        assertRejected(endpoint().events("charge.paid", null), "endpoints_enabled_events_check");
    }

    @Test
    void rejectsTheWildcardBesideOtherTypes() {
        assertRejected(endpoint().events("*", "charge.paid"), "endpoints_enabled_events_check");
    }

    @Test
    void acceptsTheWildcardAlone() {
        insert(endpoint().events("*"));
    }

    @Test
    void rejectsAnUnknownStatus() {
        assertRejected(endpoint().status("PAUSED", null), "endpoints_status_check");
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILING", "BY_TENANT"})
    void acceptsADisabledEndpointWithItsReason(String reason) {
        insert(endpoint().status("DISABLED", reason));
    }

    @Test
    void rejectsADisabledEndpointWithNoReason() {
        assertRejected(endpoint().status("DISABLED", null), "endpoints_check");
    }

    @Test
    void rejectsAnEnabledEndpointWithAReason() {
        assertRejected(endpoint().status("ENABLED", "FAILING"), "endpoints_check");
    }

    @Test
    void rejectsAnUnknownDisabledReason() {
        assertRejected(endpoint().status("DISABLED", "TIRED"), "endpoints_disabled_reason_check");
    }

    @Test
    void rejectsASecretOfNoEndpoint() {
        assertThatThrownBy(() -> insertSecret(UUID.randomUUID(), tenant))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("secrets_endpoint_id_fkey");
    }

    @Test
    void rejectsASecondCurrentSecret() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);

        assertThatThrownBy(() -> insertSecret(row.id, tenant))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("secrets_current_idx");
    }

    @Test
    void keepsARolledSecretBesideTheCurrentOne() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant, Instant.now().plusSeconds(60));

        insertSecret(row.id, tenant);
    }

    @Test
    void rejectsASecretThatEndsBeforeItWasCreated() {
        var row = endpoint();
        insert(row);

        assertThatThrownBy(() -> insertSecret(row.id, tenant, Instant.now().minusSeconds(60)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("secrets_check");
    }

    @Test
    void letsTheModuleRoleInsertAndReadAnEndpoint() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));
        insert(endpoint());

        assertThat(visibleEndpoints()).isOne();
    }

    @Test
    void letsTheModuleRoleInsertASecret() {
        var row = endpoint();
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        insertSecret(row.id, tenant);
    }

    @Test
    void deniesTheModuleRoleReadingASecret() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("SELECT ciphertext FROM webhooks.secrets")
                        .query(byte[].class)
                        .list())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheModuleRoleEndTheCurrentSecret() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int updated = jdbc.sql("""
                        UPDATE webhooks.secrets SET expires_at = now() + interval '1 day'
                         WHERE endpoint_id = ? AND expires_at IS NULL
                        """).param(row.id).update();

        assertThat(updated).isOne();
        assertThat(jdbc.sql("SELECT count(*) FROM webhooks.secrets WHERE expires_at > now()")
                        .query(Long.class)
                        .single())
                .isOne();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ciphertext = '\\x00'",
                "endpoint_id = gen_random_uuid()",
                "tenant_id = gen_random_uuid()",
                "created_at = now()"
            })
    void deniesTheModuleRoleChangingASecretBeyondItsEnd(String assignment) {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE webhooks.secrets SET " + assignment + " WHERE endpoint_id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void hidesAnotherTenantsSecretFromTheModuleRole() {
        var row = endpoint().tenant(UUID.randomUUID());
        insert(row);
        insertSecret(row.id, row.tenant);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int updated = jdbc.sql("UPDATE webhooks.secrets SET expires_at = now() WHERE endpoint_id = ?")
                .param(row.id)
                .update();

        assertThat(updated).isZero();
    }

    @Test
    void deniesTheModuleRoleDeletingASecret() {
        var row = endpoint();
        insert(row);
        insertSecret(row.id, tenant);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM webhooks.secrets WHERE endpoint_id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheModuleRoleChangeAnEndpointAndMarkItDeleted() {
        var row = endpoint();
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int updated = jdbc.sql("""
                        UPDATE webhooks.endpoints
                           SET url = 'https://hooks.example.com', description = NULL, enabled_events = '{*}',
                               status = 'DISABLED', disabled_reason = 'BY_TENANT', updated_at = now(),
                               deleted_at = now()
                         WHERE id = ?
                        """).param(row.id).update();

        assertThat(updated).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id = gen_random_uuid()", "tenant_id = gen_random_uuid()", "created_at = now()"})
    void deniesTheModuleRoleChangingWhatNeverChanges(String assignment) {
        var row = endpoint();
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE webhooks.endpoints SET " + assignment + " WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void hidesAnotherTenantsEndpointFromTheModuleRolesChanges() {
        var row = endpoint().tenant(UUID.randomUUID());
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        int updated = jdbc.sql("UPDATE webhooks.endpoints SET url = 'https://attacker.example' WHERE id = ?")
                .param(row.id)
                .update();

        assertThat(updated).isZero();
    }

    @Test
    void deniesTheModuleRoleDeletingAnEndpoint() {
        var row = endpoint();
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM webhooks.endpoints WHERE id = ?")
                        .param(row.id)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void showsTheModuleRoleNoEndpointWithNoTenantBound() {
        insert(endpoint());
        DatabaseRole.WEBHOOKS.actAs(jdbc);

        assertThat(visibleEndpoints()).isZero();
    }

    @Test
    void hidesAnotherTenantsEndpointFromTheModuleRole() {
        insert(endpoint().tenant(UUID.randomUUID()));
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleEndpoints()).isZero();
    }

    @Test
    void deniesTheModuleRoleInsertingAnEndpointForAnotherTenant() {
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(endpoint().tenant(UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingAnEndpointWithNoTenantBound() {
        DatabaseRole.WEBHOOKS.actAs(jdbc);

        assertThatThrownBy(() -> insert(endpoint())).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingASecretForAnotherTenant() {
        var row = endpoint();
        insert(row);
        DatabaseRole.WEBHOOKS.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertSecret(row.id, UUID.randomUUID()))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    private long visibleEndpoints() {
        return jdbc.sql("SELECT count(*) FROM webhooks.endpoints")
                .query(Long.class)
                .single();
    }

    private void assertRejected(Endpoint row, String constraint) {
        assertThatThrownBy(() -> insert(row))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private Endpoint endpoint() {
        return new Endpoint(tenant);
    }

    private void insert(Endpoint row) {
        jdbc.sql("""
                        INSERT INTO webhooks.endpoints
                               (id, tenant_id, url, description, enabled_events, status, disabled_reason,
                                created_at, updated_at)
                        VALUES (:id, :tenant, :url, :description, :events, :status, :reason,
                                :createdAt, :createdAt)
                        """)
                .param("id", row.id)
                .param("tenant", row.tenant)
                .param("url", row.url)
                .param("description", row.description)
                .param("events", row.events)
                .param("status", row.status)
                .param("reason", row.reason)
                .param("createdAt", row.createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertSecret(UUID endpoint, UUID owner) {
        insertSecret(endpoint, owner, null);
    }

    private void insertSecret(UUID endpoint, UUID owner, @Nullable Instant expiresAt) {
        jdbc.sql("""
                        INSERT INTO webhooks.secrets (id, endpoint_id, tenant_id, ciphertext, expires_at, created_at)
                        VALUES (:id, :endpoint, :tenant, :ciphertext, :expiresAt, :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("endpoint", endpoint)
                .param("tenant", owner)
                .param("ciphertext", new byte[] {1, 2, 3})
                .param("expiresAt", expiresAt == null ? null : expiresAt.atOffset(ZoneOffset.UTC))
                .param("createdAt", Instant.now().atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A valid enabled endpoint; each method changes one column. */
    private static final class Endpoint {

        private final UUID id = UUID.randomUUID();
        private UUID tenant;
        private String url = "https://example.com/axion/webhooks";
        private @Nullable String description = "Order service";
        private @Nullable String[] events = {"charge.paid", "charge.refunded"};
        private String status = "ENABLED";
        private @Nullable String reason;
        private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        private Endpoint(UUID tenant) {
            this.tenant = tenant;
        }

        Endpoint tenant(UUID value) {
            tenant = value;
            return this;
        }

        Endpoint url(String value) {
            url = value;
            return this;
        }

        Endpoint description(String value) {
            description = value;
            return this;
        }

        Endpoint events(@Nullable String... values) {
            events = values;
            return this;
        }

        Endpoint status(String value, @Nullable String disabledReason) {
            status = value;
            reason = disabledReason;
            return this;
        }
    }
}
