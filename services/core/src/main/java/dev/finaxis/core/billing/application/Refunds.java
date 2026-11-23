package dev.finaxis.core.billing.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.RefundId;
import dev.finaxis.core.billing.RefundRefusal;
import dev.finaxis.core.billing.RefundRequest;
import dev.finaxis.core.billing.RefundResult;
import dev.finaxis.core.billing.RefundStatus;
import dev.finaxis.core.billing.RefundView;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.Payment;
import dev.finaxis.core.billing.domain.Refund;
import dev.finaxis.core.billing.domain.RefundDecision;
import dev.finaxis.core.billing.persistence.JdbcCharges;
import dev.finaxis.core.billing.persistence.JdbcPayments;
import dev.finaxis.core.billing.persistence.JdbcRefunds;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostResult;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.shared.BusinessCalendar;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantView;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Refunds one payment of a charge (billing design, section 10.3): the refund, its ledger entry and
 * what it changes of the payment and the charge commit together, in the caller's transaction.
 * Which refunds are allowed is the domain's decision; whether the tenant's balance covers one is
 * the ledger's.
 */
@Component
class Refunds {

    private static final String ROLE = "finaxis_billing";

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;
    private final LedgerApi ledger;
    private final TenancyApi tenancy;
    private final PlatformAccounts platform;
    private final ModuleRoles roles;
    private final Clock clock;
    private final UuidV7Generator ids;

    Refunds(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            LedgerApi ledger,
            TenancyApi tenancy,
            PlatformAccounts platform,
            ModuleRoles roles,
            Clock clock) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.tenancy = tenancy;
        this.platform = platform;
        this.roles = roles;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
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
        return new RefundResult.Refunded(new RefundView(
                refund.id(), charge.id(), refund.payment(), refund.amount(), RefundStatus.SUCCEEDED, now));
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
