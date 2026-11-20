package dev.finaxis.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.DatabaseRole;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.tenancy.Permission;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The API keys table and the rules the database enforces on it (tenancy design, section 3.4),
 * checked against a real PostgreSQL. Random bytes stand in for a key's hash.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class ApiKeySchemaTest {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcClient jdbc;

    @Autowired
    ApiKeySchemaTest(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Test
    void storesAKeyWithItsScopes() {
        var key = insertKey("Checkout", randomHash(), "a1B2", "{charges:read,charges:write}");

        var scopes = jdbc.sql("SELECT array_to_string(scopes, ' ') FROM access.api_keys WHERE id = ?")
                .param(key)
                .query(String.class)
                .single();

        assertThat(scopes).isEqualTo("charges:read charges:write");
    }

    @Test
    void rejectsTwoKeysWithTheSameHash() {
        var hash = randomHash();
        insertKey("First", hash, "a1B2", "{charges:read}");

        assertThatThrownBy(() -> insertKey("Second", hash, "a1B2", "{charges:read}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_secret_hash_key");
    }

    @Test
    void rejectsAHashThatIsNotSha256() {
        assertThatThrownBy(() -> insertKey("Checkout", new byte[16], "a1B2", "{charges:read}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_secret_hash_check");
    }

    @Test
    void rejectsAKeyWithoutAName() {
        assertThatThrownBy(() -> insertKey("", randomHash(), "a1B2", "{charges:read}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_name_check");
    }

    @Test
    void rejectsLastFourThatAreNotBase62() {
        assertThatThrownBy(() -> insertKey("Checkout", randomHash(), "a1_2", "{charges:read}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_last_four_check");
    }

    @Test
    void rejectsAKeyWithoutScopes() {
        assertThatThrownBy(() -> insertKey("Checkout", randomHash(), "a1B2", "{}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_scopes_check");
    }

    @Test
    void rejectsAHumanOnlyPermissionAsAScope() {
        assertThatThrownBy(() -> insertKey("Checkout", randomHash(), "a1B2", "{charges:read,api_keys:manage}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_scopes_check");
    }

    @Test
    void rejectsANullScope() {
        assertThatThrownBy(() -> insertKey("Checkout", randomHash(), "a1B2", "{charges:read,NULL}"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("api_keys_scopes_check");
    }

    /**
     * The scope list in the table's {@code CHECK} repeats {@link Permission#isApiKeyScope}. This test
     * keeps the two in step: a permission added to the enum must be added to the migration too, or
     * the test fails.
     */
    @ParameterizedTest
    @EnumSource(Permission.class)
    void acceptsAPermissionAsAScopeOnlyIfAnApiKeyMayCarryIt(Permission permission) {
        var scopes = "{" + permission.code() + "}";

        if (permission.isApiKeyScope()) {
            assertThatCode(() -> insertKey("Checkout", randomHash(), "a1B2", scopes))
                    .doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> insertKey("Checkout", randomHash(), "a1B2", scopes))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("api_keys_scopes_check");
        }
    }

    @Test
    void letsTheAccessRoleRevokeAKey() {
        DatabaseRole.ACCESS.actAs(jdbc);
        var key = insertKey("Checkout", randomHash(), "a1B2", "{charges:read}");

        var revoked = jdbc.sql("UPDATE access.api_keys SET revoked_at = now() WHERE id = ?")
                .param(key)
                .update();

        assertThat(revoked).isOne();
    }

    @Test
    void deniesTheAccessRoleChangingTheScopesOfAKey() {
        var key = insertKey("Checkout", randomHash(), "a1B2", "{charges:read}");
        DatabaseRole.ACCESS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("UPDATE access.api_keys SET scopes = '{refunds:write}' WHERE id = ?")
                        .param(key)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheAccessRoleMovingAKeyToAnotherTenant() {
        var key = insertKey("Checkout", randomHash(), "a1B2", "{charges:read}");
        DatabaseRole.ACCESS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("UPDATE access.api_keys SET tenant_id = ? WHERE id = ?")
                        .params(UUID.randomUUID(), key)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @Test
    void deniesTheAccessRoleDeletingAKey() {
        var key = insertKey("Checkout", randomHash(), "a1B2", "{charges:read}");
        DatabaseRole.ACCESS.actAs(jdbc);

        assertThatThrownBy(() -> jdbc.sql("DELETE FROM access.api_keys WHERE id = ?")
                        .param(key)
                        .update())
                .rootCause()
                .hasMessageContaining("permission denied");
    }

    @ParameterizedTest
    @ValueSource(strings = {"find_api_key(bytea)", "record_api_key_use(bytea, timestamptz, timestamptz)"})
    void restrictsTheKeyLookupToTheAccessRole(String function) {
        var lookup = jdbc.sql("""
                        SELECT p.prosecdef, pg_get_userbyid(p.proowner) AS owner,
                               has_function_privilege('finaxis_access', p.oid, 'EXECUTE') AS access,
                               has_function_privilege('finaxis_tenancy', p.oid, 'EXECUTE') AS tenancy
                          FROM pg_proc p
                         WHERE p.oid = CAST(:function AS regprocedure)
                        """)
                .param("function", "access." + function)
                .query((row, rowNumber) -> List.of(
                        row.getBoolean("prosecdef"),
                        row.getString("owner"),
                        row.getBoolean("access"),
                        row.getBoolean("tenancy")))
                .single();

        assertThat(lookup).containsExactly(true, "finaxis_access_lookup", true, false);
    }

    @Test
    void limitsHowLongTheAccessRoleWaits() {
        var settings = jdbc.sql("""
                        SELECT unnest(setconfig)
                          FROM pg_db_role_setting
                         WHERE setrole = 'finaxis_access'::regrole
                        """).query(String.class).list();

        assertThat(settings)
                .containsExactlyInAnyOrder("lock_timeout=2s", "statement_timeout=5s", "transaction_timeout=30s");
    }

    private UUID insertKey(String name, byte[] hash, String lastFour, String scopes) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO access.api_keys (id, tenant_id, name, secret_hash, last_four, scopes, created_by)
                        VALUES (?, ?, ?, ?, ?, ?::text[], ?)
                        """)
                .params(id, UUID.randomUUID(), name, hash, lastFour, scopes, UUID.randomUUID())
                .update();
        return id;
    }

    private static byte[] randomHash() {
        var hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }
}
