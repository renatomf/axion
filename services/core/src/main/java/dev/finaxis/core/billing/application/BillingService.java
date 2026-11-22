package dev.finaxis.core.billing.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.billing.BillingApi;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.ChargeView;
import dev.finaxis.core.billing.CreateChargeResult;
import dev.finaxis.core.billing.domain.BrCode;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.TransactionId;
import dev.finaxis.core.billing.persistence.JdbcCharges;
import dev.finaxis.core.ledger.AccountRequest;
import dev.finaxis.core.ledger.AccountType;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@EnableConfigurationProperties(BillingProperties.class)
public class BillingService implements BillingApi {

    private static final String ROLE = "finaxis_billing";

    public static final String CHARGES_CREATED = "finaxis.billing.charges.created";

    // A collision of 148 random bits is not expected in the life of the system; a few draws bound
    // the loop should the random source ever be broken.
    static final int MAX_TRANSACTION_ID_DRAWS = 3;

    private final JdbcCharges charges;
    private final LedgerApi ledger;
    private final TenancyApi tenancy;
    private final ModuleRoles roles;
    private final BillingProperties properties;
    private final Clock clock;
    private final SecureRandom random;
    private final UuidV7Generator ids;
    private final Counter created;

    @Autowired
    public BillingService(
            JdbcCharges charges,
            LedgerApi ledger,
            TenancyApi tenancy,
            ModuleRoles roles,
            BillingProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this(charges, ledger, tenancy, roles, properties, clock, meters, new SecureRandom());
    }

    BillingService(
            JdbcCharges charges,
            LedgerApi ledger,
            TenancyApi tenancy,
            ModuleRoles roles,
            BillingProperties properties,
            Clock clock,
            MeterRegistry meters,
            SecureRandom random) {
        this.charges = charges;
        this.ledger = ledger;
        this.tenancy = tenancy;
        this.roles = roles;
        this.properties = properties;
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
        var binding = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("Creating a charge needs a tenant bound"));
        TenantView tenant = tenancy.tenant(binding.tenant())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown tenant " + binding.tenant().value()));
        ledger.openAccount(availableAccount(tenant));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Charge charge = Charge.create(new ChargeId(ids.next()), request.amount(), now, now.plus(request.expiresIn()));
        CreateChargeResult result = roles.call(ROLE, () -> insert(tenant, charge, request));
        countOnCommit();
        return result;
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
    private CreateChargeResult insert(TenantView tenant, Charge charge, ChargeRequest request) {
        for (int draw = 1; draw <= MAX_TRANSACTION_ID_DRAWS; draw++) {
            TransactionId transactionId = TransactionId.generate(random);
            BrCode brCode = BrCode.encode(
                    properties.pixKey(tenant.environment()),
                    tenant.organizationName(),
                    properties.merchantCity(),
                    charge.amount(),
                    transactionId);
            if (charges.insertIfTransactionIdFree(tenant.id(), charge, request, transactionId, brCode)) {
                return new CreateChargeResult.Created(view(tenant, charge, request, transactionId, brCode));
            }
        }
        throw new IllegalStateException(
                "No free transaction ID in " + MAX_TRANSACTION_ID_DRAWS + " draws; is the random source broken?");
    }

    // A liability: money Finaxis owes the tenant (platform overview, section 6.3).
    private static AccountRequest availableAccount(TenantView tenant) {
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
