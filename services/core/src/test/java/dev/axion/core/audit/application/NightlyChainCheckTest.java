package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.audit.ChainBreak;
import dev.axion.core.audit.ChainId;
import dev.axion.core.audit.Verification;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * {@link NightlyChainCheck} against a real PostgreSQL. The database holds the chains of other tests
 * too, some of them broken on purpose, so each test looks only at the chains it appended.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class NightlyChainCheckTest {

    private final NightlyChainCheck check;
    private final AuditTrail trail;
    private final JdbcClient owner;
    private final MeterRegistry meters;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    NightlyChainCheckTest(NightlyChainCheck check, AuditTrail trail, DatabaseOwner owner, MeterRegistry meters) {
        this.check = check;
        this.trail = trail;
        this.owner = owner.jdbc();
        this.meters = meters;
    }

    @Test
    void publishesAnIntactChainAsVerifiedAndLogsItsHead(CapturedOutput output) {
        append(tenant);
        append(tenant);

        var verification = verificationOf(tenant);

        assertThat(verification.intact()).isTrue();
        assertThat(verified(tenant)).isEqualTo(1);
        assertThat(output)
                .contains("Audit chain " + tenant.publicId() + " is intact; head at sequence 2 with hash "
                        + verification.headHash());
    }

    @Test
    void publishesABrokenChainAsNotVerifiedAndLogsWhereItBreaks(CapturedOutput output) {
        append(tenant);
        append(tenant);
        owner.sql("DELETE FROM audit.records WHERE tenant_id = ? AND sequence = 1")
                .param(tenant.value())
                .update();

        var verification = verificationOf(tenant);

        assertThat(verification.breach()).contains(new ChainBreak(1, ChainBreak.Kind.MISSING));
        assertThat(verified(tenant)).isZero();
        assertThat(output)
                .contains("Audit chain " + tenant.publicId() + " is broken at sequence 1 (MISSING)")
                .contains("\"tenant_id\":\"" + tenant.value() + "\"");
    }

    @Test
    void verifiesAChainWhoseHeadWasRemoved() {
        append(tenant);
        owner.sql("DELETE FROM audit.chain_heads WHERE tenant_id = ?")
                .param(tenant.value())
                .update();

        assertThat(verificationOf(tenant).breach()).contains(new ChainBreak(0, ChainBreak.Kind.HEAD_MISMATCH));
    }

    @Test
    void runsEveryNightAtThreeInBrazil(@Autowired ScheduledTaskHolder scheduler) {
        assertThat(scheduler.getScheduledTasks())
                .map(ScheduledTask::getTask)
                .filteredOn(task -> task.toString().endsWith("NightlyChainCheck.verifyAll"))
                .singleElement()
                .isInstanceOfSatisfying(
                        CronTask.class, task -> assertThat(task.getExpression()).isEqualTo("0 0 3 * * *"));
    }

    private Verification verificationOf(TenantId of) {
        return check.verifyAll().stream()
                .filter(verification -> verification.chain().equals(new ChainId(of)))
                .findFirst()
                .orElseThrow();
    }

    private double verified(TenantId of) {
        return meters.get(NightlyChainCheck.VERIFIED)
                .tag("chain", of.publicId().toString())
                .gauge()
                .value();
    }

    private void append(TenantId of) {
        DomainEvent event = new TestEvent(new EventHeader(
                new EventId(UUID.randomUUID()),
                of,
                Environment.TEST,
                new Actor.Job("webhook-delivery"),
                "corr-1",
                Instant.parse("2026-11-24T15:04:05Z")));
        trail.append(event, new AuditedAction("webhook_endpoint.disabled", "webhook_endpoint", "we_1", Map.of()));
    }

    private record TestEvent(EventHeader header) implements DomainEvent {}
}
