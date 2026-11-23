package dev.axion.core.billing.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargeCursor;
import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeFilter;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.ChargePage;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.billing.CreateChargeResult;
import dev.axion.core.billing.RefundRequest;
import dev.axion.core.billing.RefundResult;
import dev.axion.core.billing.domain.BrCode;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.billing.domain.TransactionId;
import dev.axion.core.billing.persistence.JdbcCharges;
import dev.axion.core.billing.persistence.JdbcPayments;
import dev.axion.core.billing.persistence.JdbcRefunds;
import dev.axion.core.ledger.AccountRequest;
import dev.axion.core.ledger.AccountType;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.risk.ChargeDecision;
import dev.axion.core.risk.RiskApi;
import dev.axion.core.risk.RiskRule;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.tenancy.TenantView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@EnableConfigurationProperties(BillingProperties.class)
public class BillingService implements BillingApi {

    private static final String ROLE = "axion_billing";

    public static final String CHARGES_CREATED = "axion.billing.charges.created";

    // A collision of 148 random bits is not expected in the life of the system; a few draws bound
    // the loop should the random source ever be broken.
    static final int MAX_TRANSACTION_ID_DRAWS = 3;

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;
    private final Refunds refunding;
    private final LedgerApi ledger;
    private final RiskApi risk;
    private final TenancyApi tenancy;
    private final ModuleRoles roles;
    private final BillingProperties properties;
    private final Clock clock;
    private final SecureRandom random;
    private final UuidV7Generator ids;
    private final ApplicationEventPublisher events;
    private final Counter created;

    @Autowired
    public BillingService(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            Refunds refunding,
            LedgerApi ledger,
            RiskApi risk,
            TenancyApi tenancy,
            ModuleRoles roles,
            BillingProperties properties,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meters) {
        this(
                charges,
                payments,
                refunds,
                refunding,
                ledger,
                risk,
                tenancy,
                roles,
                properties,
                events,
                clock,
                meters,
                new SecureRandom());
    }

    BillingService(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            Refunds refunding,
            LedgerApi ledger,
            RiskApi risk,
            TenancyApi tenancy,
            ModuleRoles roles,
            BillingProperties properties,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meters,
            SecureRandom random) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.refunding = refunding;
        this.ledger = ledger;
        this.risk = risk;
        this.tenancy = tenancy;
        this.roles = roles;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
        this.random = random;
        this.ids = new UuidV7Generator(clock);
        // Registered at zero, so a rate is defined before the first charge.
        this.created = Counter.builder(CHARGES_CREATED)
                .description("Charges created, counted when their transaction commits")
                .register(meters);
    }

    @Override
    @Transactional
    public CreateChargeResult createCharge(ChargeRequest request) {
        var binding = bound("Creating a charge");
        if (risk.assessCharge(binding.tenant(), request.amount()) instanceof ChargeDecision.Denied(RiskRule rule)) {
            return new CreateChargeResult.LimitExceeded(rule);
        }
        TenantView tenant = tenancy.tenant(binding.tenant())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown tenant " + binding.tenant().value()));
        ledger.openAccount(availableAccount(tenant));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Charge charge = Charge.create(new ChargeId(ids.next()), request.amount(), now, now.plus(request.expiresIn()));
        ChargeView created = roles.call(ROLE, () -> insert(tenant, charge, request));
        events.publishEvent(new ChargeCreated(
                EventHeader.ofCurrentWork(new EventId(ids.next()), now),
                new ChargeDetail(created, List.of(), List.of())));
        countOnCommit();
        return new CreateChargeResult.Created(created);
    }

    // One snapshot, so a payment or a refund committed between the reads cannot appear beside a
    // charge it has not changed yet.
    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ChargeDetail> charge(ChargeId charge) {
        var binding = bound("Reading a charge");
        return roles.call(
                ROLE,
                () -> charges.find(binding, charge)
                        .map(found -> new ChargeDetail(
                                found, payments.views(found.id(), MAX_DETAIL_PAYMENTS), refunds.views(found.id()))));
    }

    @Override
    @Transactional(readOnly = true)
    public ChargePage charges(ChargeFilter filter, ChargeCursor cursor, int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE + ": " + limit);
        }
        var binding = bound("Listing charges");
        return roles.call(ROLE, () -> page(binding, filter, cursor, limit));
    }

    @Override
    @Transactional
    public RefundResult refund(ChargeId charge, RefundRequest request) {
        return refunding.refund(bound("Refunding a charge"), charge, request);
    }

    // One charge more than the limit tells whether there is a next page, so no cursor leads to an empty one.
    private ChargePage page(TenantContext.Binding binding, ChargeFilter filter, ChargeCursor cursor, int limit) {
        List<ChargeView> found = charges.list(binding, filter, cursor.after(), Math.addExact(limit, 1));
        if (found.size() <= limit) {
            return new ChargePage(found, Optional.empty());
        }
        List<ChargeView> page = found.subList(0, limit);
        return new ChargePage(
                page, Optional.of(ChargeCursor.after(page.getLast().id())));
    }

    private static TenantContext.Binding bound(String work) {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(work + " needs a tenant bound"));
    }

    // The caller's transaction may still roll back, as an idempotent request that fails later does;
    // a charge that never existed is not counted.
    private void countOnCommit() {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                created.increment();
            }
        });
    }

    // The transaction ID is unique across tenants (billing design, section 5.2), so a taken one,
    // whoever holds it, is a new draw and a new BR Code.
    private ChargeView insert(TenantView tenant, Charge charge, ChargeRequest request) {
        for (int draw = 1; draw <= MAX_TRANSACTION_ID_DRAWS; draw++) {
            TransactionId transactionId = TransactionId.generate(random);
            BrCode brCode = BrCode.encode(
                    properties.pixKey(tenant.environment()),
                    tenant.organizationName(),
                    properties.merchantCity(),
                    charge.amount(),
                    transactionId);
            if (charges.insertIfTransactionIdFree(tenant.id(), charge, request, transactionId, brCode)) {
                return view(tenant, charge, request, transactionId, brCode);
            }
        }
        throw new IllegalStateException(
                "No free transaction ID in " + MAX_TRANSACTION_ID_DRAWS + " draws; is the random source broken?");
    }

    // A liability: money Axion owes the tenant (platform overview, section 6.3).
    static AccountRequest availableAccount(TenantView tenant) {
        return new AccountRequest(
                tenant.id().value(),
                "tenant:" + tenant.id().value() + ":available",
                AccountType.LIABILITY,
                Charge.CURRENCY,
                false,
                false);
    }

    private static ChargeView view(
            TenantView tenant, Charge charge, ChargeRequest request, TransactionId transactionId, BrCode brCode) {
        return new ChargeView(
                charge.id(),
                charge.status(),
                charge.amount(),
                charge.amountRefunded(),
                request.description(),
                request.metadata(),
                transactionId.value(),
                brCode.text(),
                charge.expiresAt(),
                charge.paidAt().orElse(null),
                charge.createdAt(),
                tenant.environment());
    }
}
