package dev.axion.core.audit.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.audit.AuditApi;
import dev.axion.core.audit.ChainBreak;
import dev.axion.core.audit.ChainId;
import dev.axion.core.audit.Verification;
import dev.axion.core.audit.persistence.JdbcChainChecks;
import dev.axion.core.shared.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies every audit chain each night (events and webhooks design, section 9.3), each in its own
 * snapshot, and publishes the result per chain: 1 when it is intact, 0 when it is not. Each chain's
 * head is logged, outside the database: removing a chain's last records together with its head is
 * the one change the chain cannot show, and the head logged the night before is what shows it.
 *
 * <p>It repairs nothing: a broken chain is an incident for a person.
 */
@Component
class NightlyChainCheck {

    static final String VERIFIED = "axion.audit.chain.verified";

    private static final String ROLE = "axion_audit_checks";

    private static final Logger LOG = LoggerFactory.getLogger(NightlyChainCheck.class);

    private final AuditApi audit;
    private final JdbcChainChecks checks;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final MultiGauge verified;

    NightlyChainCheck(
            AuditApi audit,
            JdbcChainChecks checks,
            ModuleRoles roles,
            TransactionTemplate transactions,
            MeterRegistry meters) {
        this.audit = audit;
        this.checks = checks;
        this.roles = roles;
        this.transactions = transactions;
        this.verified = MultiGauge.builder(VERIFIED)
                .description("Whether the last nightly check found the tenant's audit chain intact (1) or not (0)")
                .register(meters);
    }

    // 03:00 in Brazil, when the platform is quietest; the walk reads every record of every chain.
    @Scheduled(cron = "0 0 3 * * *", zone = "America/Sao_Paulo")
    List<Verification> verifyAll() {
        var results = new ArrayList<Verification>();
        for (var tenant : chains()) {
            Verification verification = audit.verify(new ChainId(tenant));
            report(verification);
            results.add(verification);
        }
        // Replaces every row, so a chain that no longer exists stops being published.
        verified.register(
                results.stream()
                        .map(result -> MultiGauge.Row.of(
                                Tags.of(
                                        "chain",
                                        result.chain().tenant().publicId().toString()),
                                result.intact() ? 1 : 0))
                        .toList(),
                true);
        return results;
    }

    private List<TenantId> chains() {
        return Objects.requireNonNull(transactions.execute(status -> roles.call(ROLE, checks::chains)));
    }

    // tenant_id is the UUID the chain's rows carry, for the runbook's queries.
    private static void report(Verification verification) {
        TenantId tenant = verification.chain().tenant();
        if (verification.breach().isEmpty()) {
            LOG.atInfo()
                    .addKeyValue("tenant_id", tenant.value())
                    .log(
                            "Audit chain {} is intact; head at sequence {} with hash {}",
                            tenant.publicId(),
                            verification.headSequence(),
                            verification.headHash());
            return;
        }
        ChainBreak breach = verification.breach().get();
        LOG.atError()
                .addKeyValue("tenant_id", tenant.value())
                .log(
                        "Audit chain {} is broken at sequence {} ({}); head at sequence {} with hash {}",
                        tenant.publicId(),
                        breach.sequence(),
                        breach.kind(),
                        verification.headSequence(),
                        verification.headHash());
    }
}
