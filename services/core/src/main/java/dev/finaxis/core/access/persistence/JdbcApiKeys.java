package dev.finaxis.core.access.persistence;

import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApiKeys {

    /** A busy key adds one write an hour, not one per request (section 4.3). */
    public static final Duration LAST_USE_PRECISION = Duration.ofHours(1);

    private final JdbcClient jdbc;

    public JdbcApiKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // Without it, two requests could each count nine keys and both insert a tenth. Advisory, since
    // this schema has no tenant row to lock; released when the transaction ends.
    public void lockTenantKeys(TenantId tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('access.api_keys'), hashtext(CAST(:tenant AS text)))")
                .param("tenant", tenant.value())
                .query()
                .singleRow();
    }

    public int countActive(TenantId tenant, Instant now) {
        return jdbc.sql("""
                        SELECT count(*)
                          FROM access.api_keys
                         WHERE tenant_id = :tenant
                           AND revoked_at IS NULL
                           AND (expires_at IS NULL OR expires_at > :now)
                        """)
                .param("tenant", tenant.value())
                .param("now", utc(now))
                .query(Integer.class)
                .single();
    }

    public void insert(
            ApiKeyId id,
            TenantId tenant,
            String name,
            byte[] secretHash,
            String lastFour,
            Set<Permission> scopes,
            MemberId createdBy,
            Instant createdAt) {
        // Sorted, so stored scopes do not depend on set iteration order.
        String[] scopeCodes = scopes.stream().map(Permission::code).sorted().toArray(String[]::new);
        jdbc.sql("""
                        INSERT INTO access.api_keys
                               (id, tenant_id, name, secret_hash, last_four, scopes, created_by, created_at)
                        VALUES (:id, :tenant, :name, :hash, :lastFour, :scopes, :createdBy, :createdAt)
                        """)
                .param("id", id.value())
                .param("tenant", tenant.value())
                .param("name", name)
                .param("hash", secretHash)
                .param("lastFour", lastFour)
                .param("scopes", scopeCodes)
                .param("createdBy", createdBy.value())
                .param("createdAt", utc(createdAt))
                .update();
    }

    // By created_at, not ID: two UUIDv7 of the same millisecond are in random order.
    public List<ApiKeyView> findByTenant(TenantId tenant) {
        return jdbc.sql("""
                        SELECT id, name, last_four, scopes, created_at, expires_at, revoked_at, last_used_at
                          FROM access.api_keys
                         WHERE tenant_id = :tenant
                         ORDER BY created_at DESC, id DESC
                        """)
                .param("tenant", tenant.value())
                .query(JdbcApiKeys::view)
                .list();
    }

    public Optional<ApiKeyView> find(TenantId tenant, ApiKeyId id) {
        return jdbc.sql("""
                        SELECT id, name, last_four, scopes, created_at, expires_at, revoked_at, last_used_at
                          FROM access.api_keys
                         WHERE tenant_id = :tenant
                           AND id = :id
                        """)
                .param("tenant", tenant.value())
                .param("id", id.value())
                .query(JdbcApiKeys::view)
                .optional();
    }

    // Any tenant: authentication runs before a tenant is known, so the lookup bypasses the tenant policy.
    public Optional<Credential> findByHash(byte[] secretHash) {
        return jdbc.sql("""
                        SELECT id, tenant_id, scopes, expires_at, revoked_at, last_used_at
                          FROM access.find_api_key(:hash)
                        """)
                .param("hash", secretHash)
                .query((row, rowNumber) -> new Credential(
                        new ApiKeyId(row.getObject("id", UUID.class)),
                        new TenantId(row.getObject("tenant_id", UUID.class)),
                        scopes(row),
                        instant(row, "expires_at"),
                        instant(row, "revoked_at"),
                        instant(row, "last_used_at")))
                .optional();
    }

    public record Credential(
            ApiKeyId id,
            TenantId tenant,
            Set<Permission> scopes,
            Optional<Instant> expiresAt,
            Optional<Instant> revokedAt,
            Optional<Instant> lastUsedAt) {

        // Same rule as recordUse, on the row already read, so most requests skip the update.
        public boolean useIsDue(Instant now) {
            return lastUsedAt.isEmpty() || !lastUsedAt.get().isAfter(now.minus(LAST_USE_PRECISION));
        }
    }

    // By hash, like findByHash. The condition is in the update too: concurrent requests that all find
    // the key due write once, and last_used_at never moves backwards.
    public boolean recordUse(byte[] secretHash, Instant usedAt) {
        return jdbc.sql("SELECT access.record_api_key_use(:hash, :usedAt, :dueFrom)")
                .param("hash", secretHash)
                .param("usedAt", utc(usedAt))
                .param("dueFrom", utc(usedAt.minus(LAST_USE_PRECISION)))
                .query(Boolean.class)
                .single();
    }

    // The first revocation is the one on record; a concurrent second one finds it and writes nothing.
    public void revoke(TenantId tenant, ApiKeyId id, Instant revokedAt) {
        jdbc.sql("""
                        UPDATE access.api_keys
                           SET revoked_at = :revokedAt
                         WHERE tenant_id = :tenant
                           AND id = :id
                           AND revoked_at IS NULL
                        """)
                .param("revokedAt", utc(revokedAt))
                .param("tenant", tenant.value())
                .param("id", id.value())
                .update();
    }

    // A key rolls once, so its grace period is never extended; a concurrent second roll writes nothing.
    public boolean expire(TenantId tenant, ApiKeyId id, Instant expiresAt) {
        return jdbc.sql("""
                        UPDATE access.api_keys
                           SET expires_at = :expiresAt
                         WHERE tenant_id = :tenant
                           AND id = :id
                           AND revoked_at IS NULL
                           AND expires_at IS NULL
                        """)
                        .param("expiresAt", utc(expiresAt))
                        .param("tenant", tenant.value())
                        .param("id", id.value())
                        .update()
                == 1;
    }

    private static ApiKeyView view(ResultSet row, int rowNumber) throws SQLException {
        return new ApiKeyView(
                new ApiKeyId(row.getObject("id", UUID.class)),
                row.getString("name"),
                row.getString("last_four"),
                scopes(row),
                instant(row, "created_at").orElseThrow(),
                instant(row, "expires_at"),
                instant(row, "revoked_at"),
                instant(row, "last_used_at"));
    }

    private static Set<Permission> scopes(ResultSet row) throws SQLException {
        String[] codes = (String[]) row.getArray("scopes").getArray();
        return Arrays.stream(codes).map(Permission::fromCode).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    // Bound as an offset, so the JVM's time zone never shifts the stored instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
