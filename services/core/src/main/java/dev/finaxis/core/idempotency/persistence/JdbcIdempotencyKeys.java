package dev.finaxis.core.idempotency.persistence;

import dev.finaxis.core.idempotency.CapturedResponse;
import dev.finaxis.core.idempotency.Fingerprint;
import dev.finaxis.core.idempotency.IdempotencyApi;
import dev.finaxis.core.idempotency.IdempotencyKey;
import dev.finaxis.core.idempotency.IdempotentRequest;
import dev.finaxis.core.idempotency.StoredResponse;
import dev.finaxis.core.shared.TenantId;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdempotencyKeys {

    private final JdbcClient jdbc;

    public JdbcIdempotencyKeys(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Takes the key's lock until the transaction ends, or answers false at once if another
     * transaction holds it (idempotency design, section 4.1). The first half names the table, as the
     * access module's lock does, so the two kinds of lock never collide; two keys whose second halves
     * collide only wait for each other.
     */
    public boolean tryLock(TenantId tenant, IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT pg_try_advisory_xact_lock(
                                   hashtext('idempotency.keys'),
                                   hashtext(CAST(:tenant AS text) || ':' || :key))
                        """)
                .param("tenant", tenant.value())
                .param("key", key.value())
                .query(Boolean.class)
                .single();
    }

    public Optional<StoredKey> find(TenantId tenant, IdempotencyKey key) {
        return jdbc.sql("""
                        SELECT fingerprint, response_status, content_type, location, response_body, correlation_id
                          FROM idempotency.keys
                         WHERE tenant_id = :tenant
                           AND key = :key
                        """)
                .param("tenant", tenant.value())
                .param("key", key.value())
                .query((row, rowNumber) -> new StoredKey(
                        new Fingerprint(row.getBytes("fingerprint")),
                        new StoredResponse(
                                new CapturedResponse(
                                        row.getInt("response_status"),
                                        row.getString("content_type"),
                                        row.getString("location"),
                                        row.getBytes("response_body")),
                                row.getString("correlation_id"))))
                .optional();
    }

    public void insert(IdempotentRequest request, CapturedResponse response, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO idempotency.keys
                               (tenant_id, key, fingerprint, response_status, content_type, location,
                                response_body, correlation_id, created_at, expires_at)
                        VALUES (:tenant, :key, :fingerprint, :status, :contentType, :location,
                                :body, :correlationId, :createdAt, :expiresAt)
                        """)
                .param("tenant", request.tenant().value())
                .param("key", request.key().value())
                .param("fingerprint", request.fingerprint().value())
                .param("status", response.status())
                .param("contentType", response.contentType())
                .param("location", response.location().orElse(null))
                .param("body", response.body())
                .param("correlationId", request.correlationId())
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("expiresAt", createdAt.plus(IdempotencyApi.RETENTION).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A key's row: the fingerprint it is bound to and the response it replays. */
    public record StoredKey(Fingerprint fingerprint, StoredResponse response) {}
}
