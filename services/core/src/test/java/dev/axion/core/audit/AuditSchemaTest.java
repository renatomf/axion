package dev.axion.core.audit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The audit trail's tables and the rules the database enforces on them (events and webhooks design,
 * sections 9.1 and 11), checked against a real PostgreSQL as the owner; nothing is committed.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuditSchemaTest {

    private static final String HASH = "'\\x" + "ab".repeat(32) + "'";

    private final DatabaseOwner.Session session;
    private final JdbcClient jdbc;
    private final UUID tenant = UUID.randomUUID();

    @Autowired
    AuditSchemaTest(DatabaseOwner owner) {
        this.session = owner.session();
        this.jdbc = session.jdbc();
    }

    @AfterEach
    void rollBack() {
        session.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEMBER", "API_KEY", "JOB"})
    void storesARecordOfEachActorType(String actorType) {
        insertRecord(1, actorType, "{}", HASH, UUID.randomUUID());
    }

    @Test
    void rejectsAnUnknownActorType() {
        assertRejected(() -> insertRecord(1, "SYSTEM", "{}", HASH, UUID.randomUUID()), "records_actor_type_check");
    }

    @Test
    void rejectsASequenceBeforeTheFirst() {
        assertRejected(() -> insertRecord(0, "JOB", "{}", HASH, UUID.randomUUID()), "records_sequence_check");
    }

    @Test
    void rejectsTwoRecordsAtOneSequenceOfAChain() {
        insertRecord(1, "JOB", "{}", HASH, UUID.randomUUID());

        assertRejected(() -> insertRecord(1, "JOB", "{}", HASH, UUID.randomUUID()), "records_tenant_id_sequence_key");
    }

    @Test
    void rejectsASecondRecordOfOneEvent() {
        UUID event = UUID.randomUUID();
        insertRecord(1, "JOB", "{}", HASH, event);

        assertRejected(() -> insertRecord(2, "JOB", "{}", HASH, event), "records_event_id_key");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"text\"", "42"})
    void rejectsDetailsThatAreNotAnObject(String details) {
        assertRejected(() -> insertRecord(1, "JOB", details, HASH, UUID.randomUUID()), "records_details_check");
    }

    @Test
    void rejectsAHashThatIsNotSha256() {
        assertRejected(
                () -> insertRecord(1, "JOB", "{}", "'\\x" + "ab".repeat(31) + "'", UUID.randomUUID()),
                "records_hash_check");
    }

    @Test
    void storesTheHeadOfAChainNotStartedYet() {
        jdbc.sql("INSERT INTO audit.chain_heads (tenant_id, sequence, hash) VALUES (?, 0, " + HASH + ")")
                .param(tenant)
                .update();
    }

    @Test
    void rejectsAHeadBeforeTheStartOfItsChain() {
        assertRejected(
                () -> jdbc.sql("INSERT INTO audit.chain_heads (tenant_id, sequence, hash) VALUES (?, -1, " + HASH + ")")
                        .param(tenant)
                        .update(),
                "chain_heads_sequence_check");
    }

    // The hash is an SQL literal, so a test can store one of the wrong length.
    private void insertRecord(long sequence, String actorType, String details, String hash, UUID event) {
        jdbc.sql("""
                        INSERT INTO audit.records
                               (id, tenant_id, sequence, occurred_at, actor_type, actor_id, action, resource_type,
                                resource_id, correlation_id, event_id, details, previous_hash, hash)
                        VALUES (gen_random_uuid(), ?, ?, now(), ?, 'charge-expiry', 'charge.paid', 'charge',
                                'ch_1', 'corr-1', ?, ?, %s, %s)
                        """.formatted(HASH, hash))
                .params(tenant, sequence, actorType, event, details)
                .update();
    }

    private static void assertRejected(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }
}
