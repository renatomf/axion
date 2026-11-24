package dev.axion.core.audit.persistence;

import dev.axion.core.audit.domain.AuditRecord;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.shared.TenantId;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Appends to the tenants' audit chains: their heads and the records added to them. */
@Repository
public class JdbcChains {

    private final JdbcClient jdbc;

    public JdbcChains(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The tenant's chain head, locked until the transaction ends, so appends to the chain are
     * serialized. A chain with no record yet gets its head first; two first appends meet on its key,
     * and the second waits for the first.
     */
    public ChainHead lockHead(TenantId tenant) {
        jdbc.sql("""
                        INSERT INTO audit.chain_heads (tenant_id, sequence, hash) VALUES (?, 0, ?)
                        ON CONFLICT (tenant_id) DO NOTHING
                        """).params(tenant.value(), ChainHash.ZERO.bytes()).update();
        return jdbc.sql("SELECT sequence, hash FROM audit.chain_heads WHERE tenant_id = ? FOR UPDATE")
                .param(tenant.value())
                .query((row, number) -> new ChainHead(row.getLong("sequence"), ChainHash.of(row.getBytes("hash"))))
                .single();
    }

    /**
     * Inserts the record with its hashes, unless its event has one already.
     *
     * @return whether it was inserted; {@code false} if an earlier handling of the event recorded it
     */
    public boolean insert(AuditRecord record, ChainHash previous, ChainHash hash) {
        return jdbc.sql("""
                        INSERT INTO audit.records
                               (id, tenant_id, sequence, occurred_at, actor_type, actor_id, action, resource_type,
                                resource_id, correlation_id, event_id, details, previous_hash, hash)
                        VALUES (:id, :tenant, :sequence, :occurredAt, :actorType, :actorId, :action, :resourceType,
                                :resourceId, :correlationId, :event, :details, :previousHash, :hash)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                        .param("id", record.id())
                        .param("tenant", record.tenant().value())
                        .param("sequence", record.sequence())
                        .param("occurredAt", record.occurredAt().atOffset(ZoneOffset.UTC))
                        .param("actorType", record.actor().type().name())
                        .param("actorId", record.actor().id())
                        .param("action", record.action())
                        .param("resourceType", record.resourceType())
                        .param("resourceId", record.resourceId())
                        .param("correlationId", record.correlationId())
                        .param("event", record.event().value())
                        .param("details", record.details())
                        .param("previousHash", previous.bytes())
                        .param("hash", hash.bytes())
                        .update()
                == 1;
    }

    /** Moves the tenant's head, locked by {@link #lockHead}, to the record just inserted. */
    public void moveHead(TenantId tenant, ChainHead head) {
        jdbc.sql("UPDATE audit.chain_heads SET sequence = ?, hash = ? WHERE tenant_id = ?")
                .params(head.sequence(), head.hash().bytes(), tenant.value())
                .update();
    }
}
