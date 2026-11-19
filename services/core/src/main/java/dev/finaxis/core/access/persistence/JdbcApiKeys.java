package dev.finaxis.core.access.persistence;

import dev.finaxis.core.access.ApiKeyId;
import dev.finaxis.core.access.ApiKeyView;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.tenancy.MemberId;
import dev.finaxis.core.tenancy.Permission;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/**
 * Reads and writes {@code access.api_keys} (tenancy design, section 3.4), within the grants of the
 * {@code finaxis_access} role.
 *
 * <p>Runs in the caller's transaction: {@code JdbcClient} uses the connection Spring has bound to
 * the current transaction, if there is one.
 */
@Repository
public class JdbcApiKeys {

    private final JdbcClient jdbc;

    public JdbcApiKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Waits until no other transaction is issuing a key for this tenant, and keeps the others waiting
     * until this transaction ends. Without it, two requests could each count nine keys and both
     * insert a tenth.
     *
     * <p>An advisory lock is a lock on a number, not on a row: there is no tenant row in this
     * module's schema to lock. The two-number form keeps it apart from advisory locks other code may
     * take: the first number names this table, the second the tenant. Two tenants whose IDs hash to
     * the same number only wait for each other. The {@code xact} variant is released when the
     * transaction commits or rolls back, so it can never be left behind.
     */
    public void lockTenantKeys(TenantId tenant) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('access.api_keys'), hashtext(CAST(:tenant AS text)))")
                .param("tenant", tenant.value())
                .query()
                .singleRow();
    }

    /**
     * The tenant's keys that still work at this instant: neither revoked nor past the end of a roll's
     * grace period.
     */
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

    /** Stores a new key. Only the hash of its secret is written; the secret itself never is. */
    public void insert(
            ApiKeyId id,
            TenantId tenant,
            String name,
            byte[] secretHash,
            String lastFour,
            Set<Permission> scopes,
            MemberId createdBy,
            Instant createdAt) {
        // The driver sends a Java String[] as a PostgreSQL text array. Sorted, so a key's scopes
        // are stored in the same order however the caller's set iterates.
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

    /**
     * Every key of the tenant, revoked and expired ones included, newest first. IDs are UUIDv7,
     * which start with their creation time, so ordering by ID orders by issue, and the primary key's
     * index breaks ties between keys issued in the same millisecond.
     */
    public List<ApiKeyView> findByTenant(TenantId tenant) {
        return jdbc.sql("""
                        SELECT id, name, last_four, scopes, created_at, expires_at, revoked_at, last_used_at
                          FROM access.api_keys
                         WHERE tenant_id = :tenant
                         ORDER BY id DESC
                        """)
                .param("tenant", tenant.value())
                .query(JdbcApiKeys::view)
                .list();
    }

    /** The tenant's key with this ID; empty if there is none, including when another tenant has it. */
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

    /**
     * Marks the tenant's key revoked at this instant, unless it already is: the first revocation is
     * the one on record. Does nothing for a key of another tenant.
     *
     * <p>Two concurrent revocations of one key cannot both write: the first locks the row, and the
     * second, once it may proceed, finds {@code revoked_at} set and updates nothing.
     */
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

    /**
     * Sets when the tenant's key stops working, the end of a roll's grace period, if it has neither
     * been revoked nor rolled before: a key is rolled at most once, so its grace period can never be
     * extended and it never has two replacements.
     *
     * <p>As with {@link #revoke}, two concurrent rolls of one key cannot both write: the second
     * waits for the first's row lock, then finds {@code expires_at} set.
     *
     * @return whether the key was updated; false if the tenant has no such key, or it was revoked or
     *     already rolled
     */
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

    /** Maps one row to a view. A {@code RowMapper} receives each row in turn; this is one. */
    private static ApiKeyView view(ResultSet row, int rowNumber) throws SQLException {
        String[] scopes = (String[]) row.getArray("scopes").getArray();
        return new ApiKeyView(
                new ApiKeyId(row.getObject("id", UUID.class)),
                row.getString("name"),
                row.getString("last_four"),
                Arrays.stream(scopes).map(Permission::fromCode).collect(Collectors.toUnmodifiableSet()),
                instant(row, "created_at").orElseThrow(),
                instant(row, "expires_at"),
                instant(row, "revoked_at"),
                instant(row, "last_used_at"));
    }

    /** A {@code timestamptz} column as an instant; empty when the column is null. */
    private static Optional<Instant> instant(ResultSet row, String column) throws SQLException {
        return Optional.ofNullable(row.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    /** An instant as JDBC 4.2 binds it to a {@code timestamptz}, whatever the JVM's time zone. */
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
