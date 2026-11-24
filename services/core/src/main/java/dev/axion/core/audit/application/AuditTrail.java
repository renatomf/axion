package dev.axion.core.audit.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.audit.domain.AuditActor;
import dev.axion.core.audit.domain.AuditRecord;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.audit.persistence.ChainHead;
import dev.axion.core.audit.persistence.JdbcChains;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.UuidV7Generator;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appends a record to its tenant's audit chain (events and webhooks design, section 9.2): the chain's
 * head is locked, the record is hashed with the head's hash and inserted, and the head moves to it,
 * in one transaction. An event handled a second time finds its record and appends nothing.
 *
 * <p>The event's tenant is bound before the transaction begins, so Row-Level Security reads it then
 * (tenancy design, section 5.3).
 */
@Component
class AuditTrail {

    private static final String ROLE = "axion_audit";

    // Canonical JSON: keys sorted and no whitespace, so the text hashed depends only on the facts.
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final JdbcChains chains;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;

    AuditTrail(JdbcChains chains, ModuleRoles roles, TransactionTemplate transactions, Clock clock) {
        this.chains = chains;
        this.roles = roles;
        this.transactions = transactions;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * Records the action the event reports, with the event's actor, time and correlation ID.
     *
     * @return whether it was appended now; {@code false} if an earlier handling of the event did
     */
    boolean append(DomainEvent event, AuditedAction action) {
        String details = canonical(action.details());
        return TenantContext.call(
                event.tenant(),
                event.environment(),
                () -> Objects.requireNonNull(transactions.execute(transaction -> roles.call(ROLE, () -> {
                    ChainHead head = chains.lockHead(event.tenant());
                    var record = new AuditRecord(
                            ids.next(),
                            event.tenant(),
                            head.sequence() + 1,
                            event.occurredAt(),
                            AuditActor.of(event.actor()),
                            action.action(),
                            action.resourceType(),
                            action.resourceId(),
                            event.correlationId(),
                            event.id(),
                            details);
                    ChainHash hash = record.hash(head.hash());
                    if (!chains.insert(record, head.hash(), hash)) {
                        return false;
                    }
                    chains.moveHead(event.tenant(), new ChainHead(record.sequence(), hash));
                    return true;
                }))));
    }

    private static String canonical(Map<String, ?> details) {
        return CANONICAL.writeValueAsString(details);
    }
}
