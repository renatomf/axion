package dev.axion.core.audit.domain;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class AuditRecordTest {

    private static final AuditRecord RECORD = new AuditRecord(
            UUID.fromString("019ab8f0-4c2e-7a10-9f3b-5d6e7f809102"),
            new TenantId(UUID.fromString("019ab8f0-1111-7a10-8000-000000000001")),
            7,
            Instant.parse("2026-11-24T15:04:05.123456Z"),
            new AuditActor(AuditActor.Type.API_KEY, "019ab8f0-2222-7a10-8000-000000000002"),
            "api_key.issued",
            "api_key",
            "key_01jdr3b6f7g8h9j0k1m2n3p4q5",
            "corr-1",
            new EventId(UUID.fromString("019ab8f0-3333-7a10-8000-000000000003")),
            "{\"name\":\"Orders\",\"scopes\":[\"charges:read\"]}");

    private static final ChainHash PREVIOUS = ChainHash.of(new byte[] {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
        31, 32
    });

    @Test
    void hashesThePreviousHashThenEachFieldAsItsLengthAndItsBytes() throws Exception {
        var encoded = new ByteArrayOutputStream();
        encoded.write(PREVIOUS.bytes());
        for (String field : List.of(
                "019ab8f0-4c2e-7a10-9f3b-5d6e7f809102",
                "019ab8f0-1111-7a10-8000-000000000001",
                "7",
                "2026-11-24T15:04:05.123456Z",
                "API_KEY",
                "019ab8f0-2222-7a10-8000-000000000002",
                "api_key.issued",
                "api_key",
                "key_01jdr3b6f7g8h9j0k1m2n3p4q5",
                "corr-1",
                "019ab8f0-3333-7a10-8000-000000000003",
                "{\"name\":\"Orders\",\"scopes\":[\"charges:read\"]}")) {
            byte[] bytes = field.getBytes(UTF_8);
            encoded.write(ByteBuffer.allocate(4).putInt(bytes.length).array());
            encoded.write(bytes);
        }

        assertThat(RECORD.hash(PREVIOUS).bytes())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()));
    }

    // A record's hash must never change once stored: a change of encoding would break every chain.
    @Test
    void keepsTheHashOfAKnownRecord() {
        assertThat(RECORD.hash(PREVIOUS))
                .hasToString("f99236a6a278163f871ff84d72a06c314134595696cda4d7b79df039783bf7c1");
    }

    @Test
    void keepsTheTimeToTheMicrosecondAsPostgresqlStoresIt() {
        var withNanos = copy(RECORD, Instant.parse("2026-11-24T15:04:05.123456789Z"));

        assertThat(withNanos.occurredAt()).isEqualTo(Instant.parse("2026-11-24T15:04:05.123456Z"));
        assertThat(withNanos.hash(PREVIOUS)).isEqualTo(RECORD.hash(PREVIOUS));
    }

    @Test
    void dependsOnThePreviousHash() {
        assertThat(RECORD.hash(ChainHash.ZERO)).isNotEqualTo(RECORD.hash(PREVIOUS));
    }

    @ParameterizedTest
    @MethodSource("changes")
    void changesWithEveryField(UnaryOperator<AuditRecord> change) {
        assertThat(change.apply(RECORD).hash(PREVIOUS)).isNotEqualTo(RECORD.hash(PREVIOUS));
    }

    @Test
    void tellsApartTextSplitDifferentlyBetweenFields() {
        var split = new AuditRecord(
                RECORD.id(),
                RECORD.tenant(),
                RECORD.sequence(),
                RECORD.occurredAt(),
                RECORD.actor(),
                "api_key.issuedapi",
                "_key",
                RECORD.resourceId(),
                RECORD.correlationId(),
                RECORD.event(),
                RECORD.details());

        assertThat(split.hash(PREVIOUS)).isNotEqualTo(RECORD.hash(PREVIOUS));
    }

    @Test
    void refusesASequenceBeforeTheFirst() {
        assertThatThrownBy(() -> new AuditRecord(
                        RECORD.id(),
                        RECORD.tenant(),
                        0,
                        RECORD.occurredAt(),
                        RECORD.actor(),
                        RECORD.action(),
                        RECORD.resourceType(),
                        RECORD.resourceId(),
                        RECORD.correlationId(),
                        RECORD.event(),
                        RECORD.details()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<UnaryOperator<AuditRecord>> changes() {
        return Stream.of(
                record -> new AuditRecord(
                        UUID.randomUUID(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        new TenantId(UUID.randomUUID()),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        8,
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> copy(record, record.occurredAt().plusNanos(1_000)),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        new AuditActor(AuditActor.Type.MEMBER, record.actor().id()),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        new AuditActor(
                                AuditActor.Type.API_KEY, UUID.randomUUID().toString()),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        "api_key.revoked",
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        "member",
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        "key_other",
                        record.correlationId(),
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        "corr-2",
                        record.event(),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        new EventId(UUID.randomUUID()),
                        record.details()),
                record -> new AuditRecord(
                        record.id(),
                        record.tenant(),
                        record.sequence(),
                        record.occurredAt(),
                        record.actor(),
                        record.action(),
                        record.resourceType(),
                        record.resourceId(),
                        record.correlationId(),
                        record.event(),
                        "{\"name\":\"Orders\",\"scopes\":[]}"));
    }

    private static AuditRecord copy(AuditRecord record, Instant occurredAt) {
        return new AuditRecord(
                record.id(),
                record.tenant(),
                record.sequence(),
                occurredAt,
                record.actor(),
                record.action(),
                record.resourceType(),
                record.resourceId(),
                record.correlationId(),
                record.event(),
                record.details());
    }
}
