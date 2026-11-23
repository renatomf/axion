package dev.axion.core.access.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseRole;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
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
 * role's privileges are enough.
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

    @BeforeEach
    void actAsTheAccessRoleForTheTenant() {
        DatabaseRole.ACCESS.actAs(jdbc, tenant);
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
        var other = new TenantId(UUID.randomUUID());
        DatabaseRole.ACCESS.actAs(jdbc, other);
        insert(other, Set.of(Permission.CHARGES_READ));
        DatabaseRole.ACCESS.actAs(jdbc, tenant);
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
        assertThat(found.lastUsedAt()).isEmpty();
    }

    @Test
    void recordsTheFirstUseOfAKey() {
        var hash = randomHash();
        var id = insert(tenant, Set.of(Permission.CHARGES_READ), hash);

        assertThat(keys.recordUse(hash, NOW)).isTrue();
        assertThat(lastUsedAt(id)).isEqualTo(NOW);
    }

    @Test
    void recordsAUseOnlyOnceTheLastIsAnHourOld() {
        var recentHash = randomHash();
        var anHourOldHash = randomHash();
        var recent = insert(tenant, Set.of(Permission.CHARGES_READ), recentHash);
        var anHourOld = insert(tenant, Set.of(Permission.CHARGES_READ), anHourOldHash);
        set("last_used_at", recent, NOW.minus(Duration.ofMinutes(59)));
        set("last_used_at", anHourOld, NOW.minus(Duration.ofHours(1)));

        assertThat(keys.recordUse(recentHash, NOW)).isFalse();
        assertThat(keys.recordUse(anHourOldHash, NOW)).isTrue();
        assertThat(lastUsedAt(recent)).isEqualTo(NOW.minus(Duration.ofMinutes(59)));
        assertThat(lastUsedAt(anHourOld)).isEqualTo(NOW);
    }

    @Test
    void neverMovesTheLastUseBackwards() {
        var hash = randomHash();
        var id = insert(tenant, Set.of(Permission.CHARGES_READ), hash);
        set("last_used_at", id, NOW);

        // A request whose clock lags behind the one that last wrote.
        assertThat(keys.recordUse(hash, NOW.minus(Duration.ofMinutes(5)))).isFalse();
        assertThat(lastUsedAt(id)).isEqualTo(NOW);
    }

    @Test
    void findsTheLastUseByHashAndTellsWhetherAnotherIsDue() {
        var hash = randomHash();
        var id = insert(tenant, Set.of(Permission.CHARGES_READ), hash);
        set("last_used_at", id, NOW.minus(Duration.ofMinutes(30)));

        var found = keys.findByHash(hash).orElseThrow();

        assertThat(found.lastUsedAt()).contains(NOW.minus(Duration.ofMinutes(30)));
        assertThat(found.useIsDue(NOW)).isFalse();
        assertThat(found.useIsDue(NOW.plus(Duration.ofMinutes(30)))).isTrue();
    }

    @Test
    void findsAKeyAndRecordsItsUseWithNoTenantBound() {
        var hash = randomHash();
        var id = insert(tenant, Set.of(Permission.CHARGES_READ), hash);
        jdbc.sql("SELECT set_config('axion.tenant_id', '', true)").query().singleRow();

        assertThat(keys.findByHash(hash)).map(JdbcApiKeys.Credential::id).contains(id);
        assertThat(keys.recordUse(hash, NOW)).isTrue();
    }

    @Test
    void findsNoKeyForAnUnknownHash() {
        insert(tenant, Set.of(Permission.CHARGES_READ));

        assertThat(keys.findByHash(randomHash())).isEmpty();
    }

    @Test
    void recordsNoUseForAnUnknownHash() {
        insert(tenant, Set.of(Permission.CHARGES_READ));

        assertThat(keys.recordUse(randomHash(), NOW)).isFalse();
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

    private Instant lastUsedAt(ApiKeyId id) {
        return jdbc.sql("SELECT last_used_at FROM access.api_keys WHERE id = ?")
                .param(id.value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private void set(String column, ApiKeyId id, Instant value) {
        jdbc.sql("UPDATE access.api_keys SET " + column + " = ? WHERE id = ?")
                .params(value.atOffset(ZoneOffset.UTC), id.value())
                .update();
    }
}
