package dev.axion.core.audit.domain;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.audit.ChainBreak;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChainWalkTest {

    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Test
    void passesAnIntactChainEndingAtItsHead() {
        var chain = chain(3);

        assertThat(walk(chain, 3, chain.getLast().hash())).isEmpty();
    }

    @Test
    void passesAChainWithNoRecord() {
        assertThat(walk(List.of(), 0, ChainHash.ZERO)).isEmpty();
    }

    @Test
    void findsARecordWhoseFieldsWereChanged() {
        var chain = chain(3);
        var changed = chain.get(1);
        chain.set(1, new ChainedRecord(with(changed.record(), "api_key.revoked"), changed.previous(), changed.hash()));

        assertThat(walk(chain, 3, chain.getLast().hash())).contains(new ChainBreak(2, ChainBreak.Kind.ALTERED));
    }

    @Test
    void findsARecordChangedWithItsHashRecomputedAtTheNextLink() {
        var chain = chain(3);
        var changed = chain.get(1);
        var record = with(changed.record(), "api_key.revoked");
        chain.set(1, new ChainedRecord(record, changed.previous(), record.hash(changed.previous())));

        assertThat(walk(chain, 3, chain.getLast().hash())).contains(new ChainBreak(3, ChainBreak.Kind.UNLINKED));
    }

    @Test
    void findsARemovedRecordAtItsSequence() {
        var chain = chain(3);
        chain.remove(1);

        assertThat(walk(chain, 3, chain.getLast().hash())).contains(new ChainBreak(2, ChainBreak.Kind.MISSING));
    }

    @Test
    void findsARecordRemovedFromTheEndAtTheHead() {
        var chain = chain(3);
        var head = chain.removeLast();

        assertThat(walk(chain, 3, head.hash())).contains(new ChainBreak(3, ChainBreak.Kind.HEAD_MISMATCH));
    }

    @Test
    void findsARecordAddedPastTheHead() {
        var chain = chain(3);

        assertThat(walk(chain, 2, chain.get(1).hash())).contains(new ChainBreak(2, ChainBreak.Kind.HEAD_MISMATCH));
    }

    @Test
    void reportsOnlyTheFirstBreak() {
        var chain = chain(4);
        chain.remove(1);
        chain.remove(1);

        assertThat(walk(chain, 4, chain.getLast().hash())).contains(new ChainBreak(2, ChainBreak.Kind.MISSING));
    }

    private static java.util.Optional<ChainBreak> walk(List<ChainedRecord> records, long headSequence, ChainHash head) {
        var walk = new ChainWalk();
        records.forEach(walk::visit);
        return walk.finish(headSequence, head);
    }

    private List<ChainedRecord> chain(int length) {
        var chain = new ArrayList<ChainedRecord>();
        var previous = ChainHash.ZERO;
        for (long sequence = 1; sequence <= length; sequence++) {
            var record = new AuditRecord(
                    UUID.randomUUID(),
                    tenant,
                    sequence,
                    Instant.parse("2026-11-24T15:04:05Z").plusSeconds(sequence),
                    new AuditActor(AuditActor.Type.JOB, "webhook-delivery"),
                    "api_key.issued",
                    "api_key",
                    "key_" + sequence,
                    "corr-" + sequence,
                    new EventId(UUID.randomUUID()),
                    "{}");
            var hash = record.hash(previous);
            chain.add(new ChainedRecord(record, previous, hash));
            previous = hash;
        }
        return chain;
    }

    private static AuditRecord with(AuditRecord record, String action) {
        return new AuditRecord(
                record.id(),
                record.tenant(),
                record.sequence(),
                record.occurredAt(),
                record.actor(),
                action,
                record.resourceType(),
                record.resourceId(),
                record.correlationId(),
                record.event(),
                record.details());
    }
}
