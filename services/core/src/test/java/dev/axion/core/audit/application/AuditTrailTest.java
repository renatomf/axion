package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.audit.domain.AuditActor;
import dev.axion.core.audit.domain.AuditRecord;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link AuditTrail} against a real PostgreSQL (events and webhooks design, section 9.2). Each append
 * commits in its own transaction, so every test appends to the chain of a tenant of its own.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuditTrailTest {

    private static final AuditedAction ISSUED =
            new AuditedAction("api_key.issued", "api_key", "key_1", Map.of("name", "Orders"));

    private final AuditTrail trail;
    private final JdbcClient owner;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    AuditTrailTest(AuditTrail trail, DatabaseOwner owner) {
        this.trail = trail;
        this.owner = owner.jdbc();
    }

    @Test
    void startsAChainAtSequenceOneLinkedToZeroBytes() {
        var event = event(tenant);

        assertThat(trail.append(event, ISSUED)).isTrue();

        var stored = records(tenant).getFirst();
        assertThat(stored.record().sequence()).isOne();
        assertThat(stored.previous()).isEqualTo(ChainHash.ZERO);
        assertThat(stored.hash()).isEqualTo(stored.record().hash(ChainHash.ZERO));
    }

    @Test
    void recordsTheEventsActorTimeAndCorrelationId() {
        var event = event(tenant);

        trail.append(event, ISSUED);

        var record = records(tenant).getFirst().record();
        assertThat(record.event()).isEqualTo(event.id());
        assertThat(record.occurredAt()).isEqualTo(event.occurredAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(record.actor()).isEqualTo(AuditActor.of(event.actor()));
        assertThat(record.correlationId()).isEqualTo(event.correlationId());
        assertThat(record.action()).isEqualTo("api_key.issued");
        assertThat(record.resourceType()).isEqualTo("api_key");
        assertThat(record.resourceId()).isEqualTo("key_1");
    }

    @Test
    void linksEachRecordToTheOneBeforeIt() {
        trail.append(event(tenant), ISSUED);
        trail.append(event(tenant), ISSUED);

        var stored = records(tenant);
        assertThat(stored).extracting(record -> record.record().sequence()).containsExactly(1L, 2L);
        assertThat(stored.get(1).previous()).isEqualTo(stored.get(0).hash());
        assertThat(stored.get(1).hash())
                .isEqualTo(stored.get(1).record().hash(stored.get(0).hash()));
    }

    @Test
    void movesTheHeadToTheLastRecord() {
        trail.append(event(tenant), ISSUED);
        trail.append(event(tenant), ISSUED);

        var last = records(tenant).getLast();
        assertThat(owner.sql("SELECT sequence, hash FROM audit.chain_heads WHERE tenant_id = ?")
                        .param(tenant.value())
                        .query((row, number) -> List.of(row.getLong("sequence"), ChainHash.of(row.getBytes("hash"))))
                        .single())
                .containsExactly(2L, last.hash());
    }

    @Test
    void storesTheDetailsAsCanonicalJson() {
        var details = new AuditedAction(
                "charge.refunded", "refund", "rf_1", Map.of("reason", "duplicate", "amount", 1050, "currency", "BRL"));

        trail.append(event(tenant), details);

        assertThat(records(tenant).getFirst().record().details())
                .isEqualTo("{\"amount\":1050,\"currency\":\"BRL\",\"reason\":\"duplicate\"}");
    }

    @Test
    void appendsNothingForAnEventAlreadyRecorded() {
        var event = event(tenant);
        trail.append(event, ISSUED);

        assertThat(trail.append(event, ISSUED)).isFalse();

        assertThat(records(tenant)).hasSize(1);
        assertThat(headSequence(tenant)).isOne();
    }

    @Test
    void keepsTheChainsOfTwoTenantsApart() {
        var other = new TenantId(UUID.randomUUID());
        trail.append(event(tenant), ISSUED);

        trail.append(event(other), ISSUED);

        assertThat(records(other).getFirst().record().sequence()).isOne();
        assertThat(records(other).getFirst().previous()).isEqualTo(ChainHash.ZERO);
    }

    @Test
    void serializesConcurrentAppendsToOneChain() throws InterruptedException, ExecutionException {
        int appends = 16;

        var appended = concurrently(appends, () -> trail.append(event(tenant), ISSUED));

        assertThat(appended).containsOnly(true);
        var stored = records(tenant);
        assertThat(stored)
                .extracting(record -> record.record().sequence())
                .containsExactlyElementsOf(sequences(appends));
        for (int i = 1; i < stored.size(); i++) {
            assertThat(stored.get(i).previous()).isEqualTo(stored.get(i - 1).hash());
        }
    }

    @Test
    void recordsAnEventHandledConcurrentlyOnce() throws InterruptedException, ExecutionException {
        var event = event(tenant);

        var appended = concurrently(8, () -> trail.append(event, ISSUED));

        assertThat(appended).containsOnlyOnce(true);
        assertThat(records(tenant)).hasSize(1);
        assertThat(headSequence(tenant)).isOne();
    }

    // A latch holds the threads until all of them are ready, so the appends start together.
    private static List<Boolean> concurrently(int count, Callable<Boolean> append)
            throws InterruptedException, ExecutionException {
        var ready = new CountDownLatch(count);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<Boolean>>();
        try (var threads = Executors.newFixedThreadPool(count)) {
            for (int i = 0; i < count; i++) {
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    start.await();
                    return append.call();
                }));
            }
            ready.await();
            start.countDown();
        }
        var results = new ArrayList<Boolean>();
        for (var future : futures) {
            results.add(future.get());
        }
        return results;
    }

    private static List<Long> sequences(int count) {
        var sequences = new ArrayList<Long>();
        for (long sequence = 1; sequence <= count; sequence++) {
            sequences.add(sequence);
        }
        return sequences;
    }

    private long headSequence(TenantId of) {
        return owner.sql("SELECT sequence FROM audit.chain_heads WHERE tenant_id = ?")
                .param(of.value())
                .query(Long.class)
                .single();
    }

    private List<StoredRecord> records(TenantId of) {
        return owner.sql("SELECT * FROM audit.records WHERE tenant_id = ? ORDER BY sequence")
                .param(of.value())
                .query((row, number) -> new StoredRecord(
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

    private static TestEvent event(TenantId of) {
        return new TestEvent(new EventHeader(
                new EventId(UUID.randomUUID()),
                of,
                Environment.TEST,
                new Actor.ApiKey(new ApiKeyId(UUID.randomUUID())),
                "corr-" + UUID.randomUUID(),
                Instant.parse("2026-11-24T15:04:05.123456789Z")));
    }

    private record StoredRecord(AuditRecord record, ChainHash previous, ChainHash hash) {}

    private record TestEvent(EventHeader header) implements DomainEvent {}
}
