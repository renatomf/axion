package dev.axion.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.TenantId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * What the audit module's roles may do with the records and chain heads (events and webhooks design,
 * sections 9.2 and 11), checked against a real PostgreSQL. The owner arranges the rows, then a role
 * acts; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuditRolesTest {

    private static final String RLS_VIOLATION = "violates row-level security policy";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();

    @Autowired
    AuditRolesTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @Test
    void letsTheModuleRoleAppendToItsTenantsChain() {
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        insertHead(tenant);
        insertRecord(tenant);
        assertThat(jdbc.sql("UPDATE audit.chain_heads SET sequence = 1").update())
                .isOne();
    }

    @Test
    void showsTheModuleRoleOnlyItsTenantsChain() {
        insertHead(tenant);
        insertRecord(tenant);
        insertHead(other);
        insertRecord(other);
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThat(jdbc.sql("SELECT tenant_id FROM audit.records")
                        .query(UUID.class)
                        .list())
                .containsExactly(tenant);
        assertThat(jdbc.sql("SELECT tenant_id FROM audit.chain_heads")
                        .query(UUID.class)
                        .list())
                .containsExactly(tenant);
    }

    @Test
    void deniesTheModuleRoleAppendingToAnotherTenantsChain() {
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertRecord(other)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleStartingAnotherTenantsChain() {
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> insertHead(other)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleAppendingWithNoTenantBound() {
        DatabaseRole.AUDIT.actAs(jdbc);

        assertThatThrownBy(() -> insertRecord(tenant)).rootCause().hasMessageContaining(RLS_VIOLATION);
    }

    @Test
    void deniesTheModuleRoleChangingARecord() {
        insertRecord(tenant);
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("UPDATE audit.records SET action = 'api_key.revoked'")
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleRemovingARecord() {
        insertRecord(tenant);
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.records").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheModuleRoleRemovingAHead() {
        insertHead(tenant);
        DatabaseRole.AUDIT.actAs(jdbc, new TenantId(tenant));

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.chain_heads").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void letsTheChecksRoleReadEveryChain() {
        insertHead(tenant);
        insertRecord(tenant);
        insertHead(other);
        insertRecord(other);
        DatabaseRole.AUDIT_CHECKS.actAs(jdbc);

        assertThat(jdbc.sql("SELECT tenant_id FROM audit.records")
                        .query(UUID.class)
                        .list())
                .contains(tenant, other);
        assertThat(jdbc.sql("SELECT tenant_id FROM audit.chain_heads")
                        .query(UUID.class)
                        .list())
                .contains(tenant, other);
    }

    @Test
    void deniesTheChecksRoleAppendingARecord() {
        DatabaseRole.AUDIT_CHECKS.actAs(jdbc);

        assertThatThrownBy(() -> insertRecord(tenant)).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheChecksRoleMovingAHead() {
        insertHead(tenant);
        DatabaseRole.AUDIT_CHECKS.actAs(jdbc);

        assertThatThrownBy(() ->
                        jdbc.sql("UPDATE audit.chain_heads SET sequence = 1").update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    private void insertHead(UUID owner) {
        jdbc.sql("INSERT INTO audit.chain_heads (tenant_id, sequence, hash) VALUES (?, 0, ?)")
                .params(owner, new byte[32])
                .update();
    }

    private void insertRecord(UUID owner) {
        jdbc.sql("""
                        INSERT INTO audit.records
                               (id, tenant_id, sequence, occurred_at, actor_type, actor_id, action, resource_type,
                                resource_id, correlation_id, event_id, details, previous_hash, hash)
                        VALUES (gen_random_uuid(), ?, 1, now(), 'JOB', 'charge-expiry', 'charge.paid', 'charge',
                                'ch_1', 'corr-1', gen_random_uuid(), '{}', ?, ?)
                        """).params(owner, new byte[32], new byte[32]).update();
    }
}
