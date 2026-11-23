package dev.axion.core.billing.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.RefundId;
import dev.axion.core.billing.RefundRefusal;
import dev.axion.core.billing.RefundRequest;
import dev.axion.core.billing.RefundResult;
import dev.axion.core.billing.RefundStatus;
import dev.axion.core.billing.RefundView;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.billing.domain.Payment;
import dev.axion.core.billing.domain.Refund;
import dev.axion.core.billing.domain.RefundDecision;
import dev.axion.core.billing.persistence.JdbcCharges;
import dev.axion.core.billing.persistence.JdbcPayments;
import dev.axion.core.billing.persistence.JdbcRefunds;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.ledger.EntryRequest;
import dev.axion.core.ledger.EntrySource;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.ledger.PostResult;
import dev.axion.core.ledger.PostingRequest;
import dev.axion.core.shared.BusinessCalendar;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.tenancy.TenantView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Refunds one payment of a charge (billing design, section 10.3): the refund, its ledger entry and
 * what it changes of the payment and the charge commit together, in the caller's transaction.
 * Which refunds are allowed is the domain's decision; whether the tenant's balance covers one is
 * the ledger's.
 */
@Component
public class Refunds {

    public static final String REFUNDS = "finaxis.billing.refunds";

    private static final String ROLE = "finaxis_billing";

    private static final Logger LOG = LoggerFactory.getLogger(Refunds.class);

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;
    private final LedgerApi ledger;
    private final TenancyApi tenancy;
    private final PlatformAccounts platform;
    private final ModuleRoles roles;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final Counter made;

    Refunds(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            LedgerApi ledger,
            TenancyApi tenancy,
            PlatformAccounts platform,
            ModuleRoles roles,
            Clock clock,
            MeterRegistry meters) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.tenancy = tenancy;
        this.platform = platform;
        this.roles = roles;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
        // Registered at zero, so a rate is defined before the first refund.
        this.made = Counter.builder(REFUNDS)
                .description("Refunds made, counted when their transaction commits")
                .register(meters);
    }

    /** Runs in the caller's transaction, with the tenant bound. */
    RefundResult refund(TenantContext.Binding binding, ChargeId chargeId, RefundRequest request) {
        // Locked before the ledger's rows, the lock order the ledger asks of its callers.
        Optional<Charge> locked = roles.call(
                ROLE,
                () -> charges.lock(binding, chargeId)
                        .map(row -> row.with(payments.ofCharge(row.id()), refunds.ofCharge(row.id()))));
        if (locked.isEmpty()) {
            return new RefundResult.Refused(RefundRefusal.CHARGE_NOT_FOUND);
        }
        Charge charge = locked.get();

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        RefundDecision decision = charge.refund(new RefundId(ids.next()), request.payment(), request.amount(), now);
        if (decision instanceof RefundDecision.Refused(RefundRefusal reason)) {
            return new RefundResult.Refused(reason);
        }
        var refunded = (RefundDecision.Refunded) decision;
        Refund refund = refunded.refund();
        Charge after = refunded.charge();

        TenantView tenant = tenancy.tenant(binding.tenant())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown tenant " + binding.tenant().value()));
        EntryId paymentEntry = roles.call(ROLE, () -> payments.entryOf(refund.payment()));
        Optional<EntryId> entry = post(tenant, refund, paymentEntry, request.correlationId());
        if (entry.isEmpty()) {
            return new RefundResult.Refused(RefundRefusal.INSUFFICIENT_BALANCE);
        }

        roles.call(ROLE, () -> {
            refunds.insert(tenant.id(), charge.id(), refund, entry.get(), request.correlationId());
            payments.addRefunded(refund.payment(), refund.amount());
            if (isOfSettlingPayment(after, refund)) {
                charges.updateRefunded(charge.id(), after.status(), after.amountRefunded(), now);
            }
            return null;
        });
        observeOnCommit(charge, refund, request.correlationId());
        return new RefundResult.Refunded(new RefundView(
                refund.id(), charge.id(), refund.payment(), refund.amount(), RefundStatus.SUCCEEDED, now));
    }

    // Logged and counted once the transaction commits: the caller's transaction, an idempotent
    // request's, may still roll the refund back.
    private void observeOnCommit(Charge charge, Refund refund, String correlationId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOG.info(
                        "Refund {} of request {} returned {} of payment {} of charge {}",
                        refund.id().publicId(),
                        correlationId,
                        refund.amount(),
                        refund.payment().publicId(),
                        charge.id().publicId());
                made.increment();
            }
        });
    }

    // A refund of a flagged payment leaves the charge as it was: the charge's status and
    // amount_refunded describe its settling payment only (section 3.2).
    private static boolean isOfSettlingPayment(Charge charge, Refund refund) {
        return charge.settlingPayment()
                .map(Payment::id)
                .filter(refund.payment()::equals)
                .isPresent();
    }

    // The reverse of the payment's entry, for the refund's amount (section 8.3). Empty when the
    // tenant's balance cannot cover it: the ledger has then rolled back to its savepoint, and
    // billing has written nothing yet.
    private Optional<EntryId> post(TenantView tenant, Refund refund, EntryId paymentEntry, String correlationId) {
        AccountId available = ledger.openAccount(BillingService.availableAccount(tenant));
        AccountId settlement = platform.settlement(tenant.environment());
        var request = new EntryRequest(
                tenant.id().value(),
                new EntrySource("refund", refund.id().value().toString(), "refund"),
                BusinessCalendar.businessDateOf(refund.createdAt()),
                correlationId,
                Optional.of(paymentEntry),
                List.of(
                        PostingRequest.debit(available, refund.amount()),
                        PostingRequest.credit(settlement, refund.amount())));
        return switch (ledger.post(request)) {
            case PostResult.Posted(EntryId posted) -> Optional.of(posted);
            case PostResult.InsufficientFunds shortOf -> Optional.empty();
            case PostResult.AlreadyPosted already ->
                throw new IllegalStateException("Refund " + refund.id().value() + " was posted before it was made");
        };
    }
}
