package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.ModuleRoles;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.audit.AuditApi;
import dev.axion.core.audit.ChainBreak;
import dev.axion.core.audit.ChainId;
import dev.axion.core.audit.Verification;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.audit.persistence.JdbcChainChecks;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AuditApi#verify} against a real PostgreSQL (events and webhooks design, section 9.3). Each
 * test appends a chain of three records for a tenant of its own, then changes it as the owner, the
 * one role that can.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ChainVerificationTest {

    private final AuditApi audit;
    private final AuditTrail trail;
    private final JdbcClient owner;
    private final TenantId tenant = new TenantId(UUID.randomUUID());
    private final ChainId chain = new ChainId(tenant);

    @Autowired
    ChainVerificationTest(AuditApi audit, AuditTrail trail, DatabaseOwner owner) {
        this.audit = audit;
        this.trail = trail;
        this.owner = owner.jdbc();
    }

    @BeforeEach
    void appendThreeRecords() {
        for (int i = 0; i < 3; i++) {
            append(tenant);
        }
    }

    @Test
    void passesAnIntactChainWithItsHead() {
        var verification = audit.verify(chain);

        assertThat(verification.intact()).isTrue();
        assertThat(verification.headSequence()).isEqualTo(3);
        assertThat(verification.headHash()).isEqualTo(hashAt(3).toString());
    }

    @Test
    void passesATenantWithNoChain() {
        var verification = audit.verify(new ChainId(new TenantId(UUID.randomUUID())));

        assertThat(verification.intact()).isTrue();
        assertThat(verification.headSequence()).isZero();
        assertThat(verification.headHash()).isEqualTo(ChainHash.ZERO.toString());
    }

    @Test
    void failsAChainWithAChangedRecord() {
        change("UPDATE audit.records SET details = '{\"name\":\"Other\"}' WHERE tenant_id = ? AND sequence = 2");

        assertThat(audit.verify(chain).breach()).contains(new ChainBreak(2, ChainBreak.Kind.ALTERED));
    }

    @Test
    void failsAChainWithARemovedRecord() {
        change("DELETE FROM audit.records WHERE tenant_id = ? AND sequence = 2");

        assertThat(audit.verify(chain).breach()).contains(new ChainBreak(2, ChainBreak.Kind.MISSING));
    }

    @Test
    void failsAChainWithReorderedRecords() {
        change("UPDATE audit.records SET sequence = 100 WHERE tenant_id = ? AND sequence = 2");
        change("UPDATE audit.records SET sequence = 2 WHERE tenant_id = ? AND sequence = 3");
        change("UPDATE audit.records SET sequence = 3 WHERE tenant_id = ? AND sequence = 100");

        assertThat(audit.verify(chain).breach()).contains(new ChainBreak(2, ChainBreak.Kind.ALTERED));
    }

    @Test
    void failsAChainWhoseLastRecordWasRemoved() {
        change("DELETE FROM audit.records WHERE tenant_id = ? AND sequence = 3");

        assertThat(audit.verify(chain).breach()).contains(new ChainBreak(3, ChainBreak.Kind.HEAD_MISMATCH));
    }

    @Test
    void leavesOneChainIntactWhenAnotherIsChanged() {
        var other = new TenantId(UUID.randomUUID());
        append(other);
        owner.sql("DELETE FROM audit.records WHERE tenant_id = ?")
                .param(other.value())
                .update();

        assertThat(audit.verify(chain).intact()).isTrue();
        assertThat(audit.verify(new ChainId(other)).intact()).isFalse();
    }

    @Test
    void walksAChainLongerThanAPage(
            @Autowired JdbcChainChecks checks,
            @Autowired ModuleRoles roles,
            @Autowired PlatformTransactionManager transactionManager) {
        var paged = new ChainVerification(checks, roles, 2);
        var transactions = new TransactionTemplate(transactionManager);
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        Verification intact = transactions.execute(status -> paged.verify(chain));
        assertThat(intact).isNotNull();
        assertThat(intact.intact()).isTrue();
        change("DELETE FROM audit.records WHERE tenant_id = ? AND sequence = 3");
        Verification broken = transactions.execute(status -> paged.verify(chain));
        assertThat(broken).isNotNull();
        assertThat(broken.breach()).contains(new ChainBreak(3, ChainBreak.Kind.HEAD_MISMATCH));
    }

    private void change(String statement) {
        owner.sql(statement).param(tenant.value()).update();
    }

    private ChainHash hashAt(long sequence) {
        return ChainHash.of(owner.sql("SELECT hash FROM audit.records WHERE tenant_id = ? AND sequence = ?")
                .params(tenant.value(), sequence)
                .query(byte[].class)
                .single());
    }

    private void append(TenantId of) {
        DomainEvent event = new TestEvent(new EventHeader(
                new EventId(UUID.randomUUID()),
                of,
                Environment.TEST,
                new Actor.Member(new MemberId(UUID.randomUUID())),
                "corr-1",
                Instant.parse("2026-11-24T15:04:05Z")));
        trail.append(event, new AuditedAction("api_key.issued", "api_key", "key_1", Map.of("name", "Orders")));
    }

    private record TestEvent(EventHeader header) implements DomainEvent {}
}
