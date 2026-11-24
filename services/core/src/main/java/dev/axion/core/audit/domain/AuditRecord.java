package dev.axion.core.audit.domain;

import static java.nio.charset.StandardCharsets.UTF_8;

import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * One security-relevant action, as the audit trail records it (events and webhooks design, section
 * 9.1): every column of a record but the two hashes.
 *
 * @param sequence the record's place in its tenant's chain, from 1
 * @param occurredAt when the action happened, kept to the microsecond, as PostgreSQL stores it
 * @param details the facts of the action as canonical JSON, the exact text that is hashed and stored
 */
public record AuditRecord(
        UUID id,
        TenantId tenant,
        long sequence,
        Instant occurredAt,
        AuditActor actor,
        String action,
        String resourceType,
        String resourceId,
        String correlationId,
        EventId event,
        String details) {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSX").withZone(ZoneOffset.UTC);

    public AuditRecord {
        if (sequence < 1) {
            throw new IllegalArgumentException("A chain's records start at sequence 1, not " + sequence);
        }
        occurredAt = occurredAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * The record's hash (section 9.2): SHA-256 over the previous record's hash, then each field in
     * the order of the table's columns, as the length of its UTF-8 bytes in four bytes, big-endian,
     * and the bytes. The lengths make the encoding unambiguous: no two different records give the
     * same bytes, however their text is split between fields.
     */
    public ChainHash hash(ChainHash previous) {
        MessageDigest sha256 = sha256();
        sha256.update(previous.bytes());
        for (String field : fields()) {
            byte[] bytes = field.getBytes(UTF_8);
            sha256.update(
                    ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            sha256.update(bytes);
        }
        return ChainHash.of(sha256.digest());
    }

    private List<String> fields() {
        return List.of(
                id.toString(),
                tenant.value().toString(),
                Long.toString(sequence),
                TIMESTAMP.format(occurredAt),
                actor.type().name(),
                actor.id(),
                action,
                resourceType,
                resourceId,
                correlationId,
                event.value().toString(),
                details);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java platform provides SHA-256", e);
        }
    }
}
