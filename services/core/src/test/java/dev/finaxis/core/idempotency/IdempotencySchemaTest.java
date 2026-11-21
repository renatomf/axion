package dev.finaxis.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.shared.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
 * The idempotency keys table and the rules the database enforces on it (idempotency design, section
 * 5), checked against a real PostgreSQL. The owner arranges the rows, then the module's role reads
 * and writes them; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class IdempotencySchemaTest {

    private static final Instant LONG_AGO = Instant.parse("2026-01-01T00:00:00Z");
    private static final String RLS_VIOLATION = "violates row-level security policy";
    private static final byte[] BODY = "{\"id\":\"ch_1\"}".getBytes(StandardCharsets.UTF_8);

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    IdempotencySchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void storesAKeyWithItsResponseAsWritten() {
        insert(row().key("order-1"));

        var body = jdbc.sql("SELECT response_body FROM idempotency.keys WHERE tenant_id = ? AND key = ?")
                .params(tenant, "order-1")
                .query(byte[].class)
                .single();

        assertThat(body).isEqualTo(BODY);
    }

    @Test
    void rejectsASecondRowForTheSameKeyOfATenant() {
        insert(row().key("order-1"));

        assertThatThrownBy(() -> insert(row().key("order-1")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("keys_pkey");
    }

    @Test
    void keepsOneKeyOfTwoTenantsAsTwoRows() {
        insert(row().key("order-1"));
        insert(row().tenant(UUID.randomUUID()).key("order-1"));

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency.keys WHERE key = 'order-1'")
                        .query(Long.class)
                        .single())
                .isEqualTo(2);
    }

    @Test
    void acceptsAKeyOfTheMaximumLength() {
        insert(row().key("k".repeat(255)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "with space", "café", "tab\tkey"})
    void rejectsAKeyOutsideVisibleAscii(String key) {
        assertRejected(row().key(key), "keys_key_check");
    }

    @Test
    void rejectsAKeyLongerThanTheMaximum() {
        assertRejected(row().key("k".repeat(256)), "keys_key_check");
    }

    @Test
    void rejectsAFingerprintThatIsNotSha256() {
        assertRejected(row().fingerprint(new byte[16]), "keys_fingerprint_check");
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 500, 503})
    void rejectsAStatusOutsideTheStoredRange(int status) {
        assertRejected(row().status(status), "keys_response_status_check");
    }

    @Test
    void rejectsAnEmptyContentType() {
        assertRejected(row().contentType(""), "keys_content_type_check");
    }

    @Test
    void acceptsABodyOf64KibAndRejectsALargerOne() {
        insert(row().key("at-limit").body(new byte[65536]));

        assertRejected(row().key("over-limit").body(new byte[65537]), "keys_response_body_check");
    }

    @Test
    void rejectsAnExpiryOtherThan24HoursAfterCreation() {
        var row = row();

        assertRejected(row.expiresAt(row.createdAt.plus(Duration.ofHours(23))), "keys_retention_check");
    }

    @Test
    void letsTheModuleRoleInsertAndReadAKey() {
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        insert(row().key("order-1"));

        assertThat(statusOf("order-1")).isEqualTo(201);
    }

    // A denied statement aborts the transaction, so each test tries one.
    @Test
    void deniesTheModuleRoleChangingAStoredResponse() {
        insert(row().key("order-1"));
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE idempotency.keys SET response_status = 200 WHERE key = 'order-1'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleDeletingAKey() {
        insert(row().key("order-1"));
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM idempotency.keys WHERE key = 'order-1'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void showsTheModuleRoleNoKeyWithNoTenantBound() {
        insert(row().key("order-1"));
        DatabaseRole.IDEMPOTENCY.actAs(jdbc);

        assertThat(visibleKeys("order-1")).isZero();
    }

    @Test
    void hidesAnotherTenantsKeyFromTheModuleRole() {
        var other = UUID.randomUUID();
        insert(row().tenant(other).key("order-1"));
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleKeys("order-1")).isZero();
    }

    @Test
    void letsATenantUseAKeyAnotherTenantHolds() {
        insert(row().tenant(UUID.randomUUID()).key("order-1"));
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        insert(row().key("order-1"));

        assertThat(visibleKeys("order-1")).isOne();
    }

    @Test
    void deniesTheModuleRoleInsertingAKeyForAnotherTenant() {
        DatabaseRole.IDEMPOTENCY.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insert(row().tenant(UUID.randomUUID())))
                .rootCause()
                .hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleInsertingAKeyWithNoTenantBound() {
        DatabaseRole.IDEMPOTENCY.actAs(jdbc);

        assertThatThrownBy(() -> insert(row())).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void showsTheJobsRoleExpiredKeysOnly() {
        var other = UUID.randomUUID();
        insert(row().key("honored"));
        insert(row().tenant(other).key("expired").expired());
        DatabaseRole.IDEMPOTENCY_JOBS.actAs(jdbc);

        assertThat(jdbc.sql("SELECT key FROM idempotency.keys WHERE tenant_id IN (?, ?)")
                        .params(tenant, other)
                        .query(String.class)
                        .list())
                .containsExactly("expired");
    }

    @Test
    void hidesHonoredKeysFromTheJobsRoleEvenWithATenantBound() {
        insert(row().key("honored"));
        DatabaseRole.IDEMPOTENCY_JOBS.actAs(jdbc, new TenantId(tenant));

        assertThat(visibleKeys("honored")).isZero();
        assertThat(deleteTheTenantsKeys()).isZero();
    }

    @Test
    void letsTheJobsRoleDeleteExpiredKeysOnly() {
        insert(row().key("honored"));
        insert(row().key("expired").expired());
        DatabaseRole.IDEMPOTENCY_JOBS.actAs(jdbc);

        assertThat(deleteTheTenantsKeys()).isOne();
    }

    @Test
    void deniesTheJobsRoleInsertingAKey() {
        DatabaseRole.IDEMPOTENCY_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> insert(row().expired())).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheJobsRoleChangingAKey() {
        insert(row().key("expired").expired());
        DatabaseRole.IDEMPOTENCY_JOBS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("UPDATE idempotency.keys SET expires_at = expires_at + interval '1 day'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private int deleteTheTenantsKeys() {
        return jdbc.sql("DELETE FROM idempotency.keys WHERE tenant_id = ?")
                .param(tenant)
                .update();
    }

    private long visibleKeys(String key) {
        return jdbc.sql("SELECT count(*) FROM idempotency.keys WHERE key = ?")
                .param(key)
                .query(Long.class)
                .single();
    }

    private int statusOf(String key) {
        return jdbc.sql("SELECT response_status FROM idempotency.keys WHERE tenant_id = ? AND key = ?")
                .params(tenant, key)
                .query(Integer.class)
                .single();
    }

    private void assertRejected(Row row, String constraint) {
        assertThatThrownBy(() -> insert(row))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private Row row() {
        return new Row(tenant);
    }

    private void insert(Row row) {
        jdbc.sql("""
                        INSERT INTO idempotency.keys
                               (tenant_id, key, fingerprint, response_status, content_type, location,
                                response_body, correlation_id, created_at, expires_at)
                        VALUES (?, ?, ?, ?, ?, '/v1/charges/ch_1', ?, 'test', ?, ?)
                        """)
                .params(
                        row.tenant,
                        row.key,
                        row.fingerprint,
                        row.status,
                        row.contentType,
                        row.body,
                        row.createdAt.atOffset(ZoneOffset.UTC),
                        row.expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A valid row of the tenant; each method changes one column. */
    private static final class Row {

        private UUID tenant;
        private String key = "order-" + UUID.randomUUID();
        private byte[] fingerprint = new byte[32];
        private int status = 201;
        private String contentType = "application/json";
        private byte[] body = BODY;
        // Created now, so the key is still honored.
        private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        private Instant expiresAt = createdAt.plus(Duration.ofHours(24));

        private Row(UUID tenant) {
            this.tenant = tenant;
        }

        Row tenant(UUID value) {
            tenant = value;
            return this;
        }

        Row key(String value) {
            key = value;
            return this;
        }

        Row fingerprint(byte[] value) {
            fingerprint = value;
            return this;
        }

        Row status(int value) {
            status = value;
            return this;
        }

        Row contentType(String value) {
            contentType = value;
            return this;
        }

        Row body(byte[] value) {
            body = value;
            return this;
        }

        /** Created long ago, so the key has expired. */
        Row expired() {
            createdAt = LONG_AGO;
            expiresAt = LONG_AGO.plus(Duration.ofHours(24));
            return this;
        }

        Row expiresAt(Instant value) {
            expiresAt = value;
            return this;
        }
    }
}
