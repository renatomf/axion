package dev.finaxis.core.access.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link JdbcApiKeys} against a real PostgreSQL, as the access role, so the tests also prove the
 * role's privileges are enough. Each test runs in a transaction that is rolled back when it ends.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@Transactional
class JdbcApiKeysTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private final JdbcApiKeys keys;
    private final JdbcClient jdbc;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    JdbcApiKeysTest(JdbcApiKeys keys, JdbcClient jdbc) {
        this.keys = keys;
        this.jdbc = jdbc;
    }

    /** Runs inside the test's transaction, so the role lasts until it is rolled back. */
    @BeforeEach
    void actAsTheAccessRole() {
        jdbc.sql("SET LOCAL ROLE finaxis_access").update();
    }

    @Test
    void storesTheScopesInCodeOrder() {
        var id = insert(tenant, Set.of(Permission.REFUNDS_WRITE, Permission.CHARGES_READ));

        var scopes = jdbc.sql("SELECT array_to_string(scopes, ' ') FROM access.api_keys WHERE id = ?")
                .param(id.value())
                .query(String.class)
                .single();

        assertThat(scopes).isEqualTo("charges:read refunds:write");
    }

    @Test
    void storesTheInstantItWasGiven() {
        var id = insert(tenant, Set.of(Permission.CHARGES_READ));

        var createdAt = jdbc.sql("SELECT created_at FROM access.api_keys WHERE id = ?")
                .param(id.value())
                .query(OffsetDateTime.class)
                .single();

        assertThat(createdAt.toInstant()).isEqualTo(NOW);
    }

    @Test
    void countsOnlyTheTenantsKeysThatStillWork() {
        insert(tenant, Set.of(Permission.CHARGES_READ));
        var revoked = insert(tenant, Set.of(Permission.CHARGES_READ));
        var expired = insert(tenant, Set.of(Permission.CHARGES_READ));
        var inGracePeriod = insert(tenant, Set.of(Permission.CHARGES_READ));
        insert(new TenantId(UUID.randomUUID()), Set.of(Permission.CHARGES_READ));
        set("revoked_at", revoked, NOW.minus(Duration.ofHours(1)));
        set("expires_at", expired, NOW);
        set("expires_at", inGracePeriod, NOW.plus(Duration.ofHours(1)));

        assertThat(keys.countActive(tenant, NOW)).isEqualTo(2);
    }

    @Test
    void findsAKeyByTheHashOfItsSecret() {
        var hash = randomHash();
        var id = insert(tenant, Set.of(Permission.CHARGES_READ, Permission.CHARGES_WRITE), hash);
        insert(tenant, Set.of(Permission.CHARGES_READ));
        set("revoked_at", id, NOW);

        var found = keys.findByHash(hash).orElseThrow();

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.tenant()).isEqualTo(tenant);
        assertThat(found.scopes()).containsExactlyInAnyOrder(Permission.CHARGES_READ, Permission.CHARGES_WRITE);
        assertThat(found.revokedAt()).contains(NOW);
        assertThat(found.expiresAt()).isEmpty();
    }

    @Test
    void findsNoKeyForAnUnknownHash() {
        insert(tenant, Set.of(Permission.CHARGES_READ));

        assertThat(keys.findByHash(randomHash())).isEmpty();
    }

    @Test
    void locksTheTenantsKeysUntilTheTransactionEnds() {
        keys.lockTenantKeys(tenant);

        var held = jdbc.sql("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()")
                .query(Integer.class)
                .single();

        assertThat(held).isOne();
    }

    private ApiKeyId insert(TenantId owner, Set<Permission> scopes) {
        return insert(owner, scopes, randomHash());
    }

    private ApiKeyId insert(TenantId owner, Set<Permission> scopes, byte[] hash) {
        var id = new ApiKeyId(UUID.randomUUID());
        keys.insert(id, owner, "Checkout", hash, "a1B2", scopes, new MemberId(UUID.randomUUID()), NOW);
        return id;
    }

    private static byte[] randomHash() {
        var hash = new byte[32];
        RANDOM.nextBytes(hash);
        return hash;
    }

    private void set(String column, ApiKeyId id, Instant value) {
        jdbc.sql("UPDATE access.api_keys SET " + column + " = ? WHERE id = ?")
                .params(value.atOffset(ZoneOffset.UTC), id.value())
                .update();
    }
}
