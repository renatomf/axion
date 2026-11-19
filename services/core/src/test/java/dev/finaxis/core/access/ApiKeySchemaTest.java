package dev.finaxis.core.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.PostgresTestConfiguration;
import java.security.SecureRandom;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The API keys table and the rules the database enforces on it (tenancy design, section 3.4),
 * checked against a real PostgreSQL.
 *
 * <p>No key appears here: a row needs only a hash, and random bytes stand in for it. Every test
 * runs inside one transaction that Spring's test support rolls back when the test ends.
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
