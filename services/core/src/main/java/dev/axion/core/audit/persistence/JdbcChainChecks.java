package dev.axion.core.audit.persistence;

import dev.axion.core.audit.domain.AuditActor;
import dev.axion.core.audit.domain.AuditRecord;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.audit.domain.ChainedRecord;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads the audit chains as they are stored, for their verification; changes nothing. */
@Repository
public class JdbcChainChecks {

    private final JdbcClient jdbc;

    public JdbcChainChecks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every tenant with a chain: with a head, or with records, so a chain whose head was removed is
     * verified too.
     */
    public List<TenantId> chains() {
        return jdbc.sql("""
                        SELECT tenant_id FROM audit.chain_heads
                        UNION
                        SELECT DISTINCT tenant_id FROM audit.records
                         ORDER BY tenant_id
                        """)
                .query((row, number) -> new TenantId(row.getObject("tenant_id", UUID.class)))
                .list();
    }

    /** The tenant's chain head; empty for a tenant that has no chain. */
    public Optional<ChainHead> head(TenantId tenant) {
        return jdbc.sql("SELECT sequence, hash FROM audit.chain_heads WHERE tenant_id = ?")
                .param(tenant.value())
                .query((row, number) -> new ChainHead(row.getLong("sequence"), ChainHash.of(row.getBytes("hash"))))
                .optional();
    }

    /** Up to {@code limit} of the chain's records after the sequence, in sequence order. */
    public List<ChainedRecord> records(TenantId tenant, long after, int limit) {
        return jdbc.sql("""
                        SELECT id, tenant_id, sequence, occurred_at, actor_type, actor_id, action, resource_type,
                               resource_id, correlation_id, event_id, details, previous_hash, hash
                          FROM audit.records
                         WHERE tenant_id = ? AND sequence > ?
                         ORDER BY sequence
                         LIMIT ?
                        """)
                .params(tenant.value(), after, limit)
                .query((row, number) -> new ChainedRecord(
                        new AuditRecord(
                                row.getObject("id", UUID.class),
                                new TenantId(row.getObject("tenant_id", UUID.class)),
                                row.getLong("sequence"),
                                row.getObject("occurred_at", OffsetDateTime.class)
                                        .toInstant(),
                                new AuditActor(
                                        AuditActor.Type.valueOf(row.getString("actor_type")),
                                        row.getString("actor_id")),
                                row.getString("action"),
                                row.getString("resource_type"),
                                row.getString("resource_id"),
                                row.getString("correlation_id"),
                                new EventId(row.getObject("event_id", UUID.class)),
                                row.getString("details")),
                        ChainHash.of(row.getBytes("previous_hash")),
                        ChainHash.of(row.getBytes("hash"))))
                .list();
    }
}
