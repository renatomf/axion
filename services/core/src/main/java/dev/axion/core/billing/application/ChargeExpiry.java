package dev.axion.core.billing.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.persistence.JdbcCharges;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ActorContext;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.tenancy.TenantView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires the active charges whose expiry has passed, tenant by tenant (billing design, section 9),
 * and publishes each one's {@link ChargeExpired} in the transaction that expired it. Whether a
 * payment settles a charge never depends on it: settlement expires a due charge itself, and judges
 * lateness by when the rails accepted the payment.
 */
@Service
public class ChargeExpiry {

    static final int BATCH_SIZE = 500;

    private static final String JOBS_ROLE = "axion_billing_jobs";

    private static final String ROLE = "axion_billing";

    private static final Actor ACTOR = new Actor.Job("charge-expiry");

    private static final Logger LOG = LoggerFactory.getLogger(ChargeExpiry.class);

    private final JdbcCharges charges;
    private final ChargeDetails details;
    private final TenancyApi tenancy;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final UuidV7Generator ids;

    public ChargeExpiry(
            JdbcCharges charges,
            ChargeDetails details,
            TenancyApi tenancy,
            ModuleRoles roles,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            Clock clock) {
        this.charges = charges;
        this.details = details;
        this.tenancy = tenancy;
        this.roles = roles;
        this.transactions = transactions;
        this.events = events;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
    }

    /**
     * Expires the due charges of every tenant that has some. A tenant whose charges fail to expire is
     * logged and left to the next run, so it never holds back the others.
     *
     * @return how many charges it expired
     */
    public int expireDue() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<TenantId> due = Objects.requireNonNull(
                transactions.execute(status -> roles.call(JOBS_ROLE, () -> charges.tenantsWithDue(now))));
        // One correlation ID for the run, which every event of the run carries.
        String run = UUID.randomUUID().toString();
        int expired = 0;
        for (TenantId tenant : due) {
            try {
                expired += expireDueOf(tenant, run);
            } catch (RuntimeException failure) {
                LOG.error("Expiring the due charges of tenant {} failed", tenant.publicId(), failure);
            }
        }
        if (expired > 0) {
            LOG.info("Expired {} charges of {} tenants in run {}", expired, due.size(), run);
        }
        return expired;
    }

    // One transaction per batch, so the job never holds its row locks for long; a batch that comes
    // back full means more may be due.
    private int expireDueOf(TenantId id, String run) {
        TenantView tenant =
                tenancy.tenant(id).orElseThrow(() -> new IllegalStateException("Unknown tenant " + id.value()));
        return TenantContext.call(
                tenant.id(),
                tenant.environment(),
                () -> ActorContext.call(ACTOR, run, () -> {
                    int expired = 0;
                    int batch;
                    do {
                        batch = Objects.requireNonNull(transactions.execute(status -> expireBatch(tenant)));
                        expired += batch;
                    } while (batch == BATCH_SIZE);
                    return expired;
                }));
    }

    // The job's role expires the charges; billing's reads what each looks like expired, with the
    // tenant bound. The events are published after both role switches return: the registry is
    // written with the login role (events and webhooks design, section 4.2).
    private int expireBatch(TenantView tenant) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<ChargeId> expired = roles.call(JOBS_ROLE, () -> charges.expireDue(tenant.id(), now, BATCH_SIZE));
        var binding = new TenantContext.Binding(tenant.id(), tenant.environment());
        List<ChargeDetail> detailed = roles.call(
                ROLE,
                () -> expired.stream()
                        .map(charge -> details.find(binding, charge)
                                .orElseThrow(() -> new IllegalStateException(
                                        "Charge " + charge.publicId() + " vanished while it expired")))
                        .toList());
        for (ChargeDetail charge : detailed) {
            events.publishEvent(new ChargeExpired(EventHeader.ofCurrentWork(new EventId(ids.next()), now), charge));
        }
        return expired.size();
    }
}
