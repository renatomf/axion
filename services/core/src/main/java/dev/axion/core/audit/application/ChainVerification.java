package dev.axion.core.audit.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.audit.AuditApi;
import dev.axion.core.audit.ChainBreak;
import dev.axion.core.audit.ChainId;
import dev.axion.core.audit.Verification;
import dev.axion.core.audit.domain.ChainHash;
import dev.axion.core.audit.domain.ChainWalk;
import dev.axion.core.audit.domain.ChainedRecord;
import dev.axion.core.audit.persistence.ChainHead;
import dev.axion.core.audit.persistence.JdbcChainChecks;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies a chain as {@code axion_audit_checks}, which may only read (events and webhooks design,
 * section 9.3). The records are read a page at a time, all in one snapshot.
 */
@Service
class ChainVerification implements AuditApi {

    static final int PAGE = 1_000;

    private static final String ROLE = "axion_audit_checks";

    private final JdbcChainChecks checks;
    private final ModuleRoles roles;
    private final int page;

    @Autowired
    ChainVerification(JdbcChainChecks checks, ModuleRoles roles) {
        this(checks, roles, PAGE);
    }

    ChainVerification(JdbcChainChecks checks, ModuleRoles roles, int page) {
        this.checks = checks;
        this.roles = roles;
        this.page = page;
    }

    // REPEATABLE READ: the head and every page are read from one snapshot, so a record appended
    // during the walk cannot look like a head that moved past the chain.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Verification verify(ChainId chain) {
        return roles.call(ROLE, () -> {
            ChainHead head = checks.head(chain.tenant()).orElse(new ChainHead(0, ChainHash.ZERO));
            var walk = new ChainWalk();
            long after = 0;
            List<ChainedRecord> records;
            do {
                records = checks.records(chain.tenant(), after, page);
                records.forEach(walk::visit);
                if (!records.isEmpty()) {
                    after = records.getLast().record().sequence();
                }
            } while (records.size() == page && !walk.broken());
            Optional<ChainBreak> breach = walk.finish(head.sequence(), head.hash());
            return new Verification(chain, head.sequence(), head.hash().toString(), breach);
        });
    }
}
