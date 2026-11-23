package dev.finaxis.core.billing.application;

import dev.finaxis.core.ModuleRoles;
import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.PaymentOutcome;
import dev.finaxis.core.billing.ReviewReason;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.IncomingPayment;
import dev.finaxis.core.billing.domain.Payment;
import dev.finaxis.core.billing.domain.Receipt;
import dev.finaxis.core.billing.domain.TransactionId;
import dev.finaxis.core.billing.persistence.JdbcCharges;
import dev.finaxis.core.billing.persistence.JdbcCharges.LockedCharge;
import dev.finaxis.core.billing.persistence.JdbcPayments;
import dev.finaxis.core.billing.persistence.JdbcRefunds;
import dev.finaxis.core.ledger.AccountId;
import dev.finaxis.core.ledger.EntryId;
import dev.finaxis.core.ledger.EntryRequest;
import dev.finaxis.core.ledger.EntrySource;
import dev.finaxis.core.ledger.LedgerApi;
import dev.finaxis.core.ledger.PostResult;
import dev.finaxis.core.ledger.PostingRequest;
import dev.finaxis.core.rails.CreditResult;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.BusinessCalendar;
import dev.finaxis.core.shared.TenantContext;
import dev.finaxis.core.shared.TenantId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.TenancyApi;
import dev.finaxis.core.tenancy.TenantView;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settles each Pix credit the rails module hands over (billing design, section 8): the charge, its
 * payment and the payment's ledger entry commit together or not at all. Every credit is credited to
 * the tenant; whether it settles the charge or is flagged is the domain's decision.
 */
@Service
public class Settlement implements PixCreditHandler {

    public static final String PAYMENTS = "finaxis.billing.payments";

    public static final String SETTLEMENT_LAG = "finaxis.billing.settlement.lag";

    private static final String ROLE = "finaxis_billing";

    private static final Logger LOG = LoggerFactory.getLogger(Settlement.class);

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;
    private final LedgerApi ledger;
    private final TenancyApi tenancy;
    private final PlatformAccounts platform;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final Map<String, Counter> recorded = new HashMap<>();
    private final Timer lag;

    public Settlement(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            LedgerApi ledger,
            TenancyApi tenancy,
            PlatformAccounts platform,
            ModuleRoles roles,
            TransactionTemplate transactions,
            Clock clock,
            MeterRegistry meters) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.ledger = ledger;
        this.tenancy = tenancy;
        this.platform = platform;
        this.roles = roles;
        this.transactions = transactions;
        this.clock = clock;
        this.ids = new UuidV7Generator(clock);
        // Registered at zero, so a rate is defined before the first payment of each kind.
        recorded.put(tags(PaymentOutcome.SETTLED, null), payments(meters, PaymentOutcome.SETTLED, null));
        for (ReviewReason reason : ReviewReason.values()) {
            recorded.put(tags(PaymentOutcome.FLAGGED, reason), payments(meters, PaymentOutcome.FLAGGED, reason));
        }
        this.lag = Timer.builder(SETTLEMENT_LAG)
                .description("From the rails' acceptance of a payment to its settlement in the core")
                .publishPercentileHistogram()
                .register(meters);
    }

    /**
     * Finds the charge's tenant from the transaction ID, then settles the credit in one transaction
     * with that tenant bound (section 8.1).
     */
    @Override
    public CreditResult handle(PixCredit credit) {
        var transactionId = new TransactionId(credit.transactionId());
        Optional<TenantId> owner = Objects.requireNonNull(
                transactions.execute(status -> roles.call(ROLE, () -> charges.tenantOf(transactionId))));
        if (owner.isEmpty()) {
            return CreditResult.UNKNOWN_TRANSACTION;
        }
        TenantView tenant = tenancy.tenant(owner.get())
                .orElseThrow(() -> new IllegalStateException(
                        "The charge of Pix credit " + credit.endToEndId() + " names an unknown tenant"));
        return TenantContext.call(
                tenant.id(),
                tenant.environment(),
                () -> Objects.requireNonNull(transactions.execute(status -> settle(tenant, transactionId, credit))));
    }

    private CreditResult settle(TenantView tenant, TransactionId transactionId, PixCredit credit) {
        // Locked before the ledger's rows, the lock order the ledger asks of its callers.
        LockedCharge locked = roles.call(ROLE, () -> charges.lockByTransactionId(transactionId))
                .orElseThrow(() -> new IllegalStateException(
                        "The charge of Pix credit " + credit.endToEndId() + " is not visible to its tenant"));
        Charge charge =
                roles.call(ROLE, () -> locked.with(payments.ofCharge(locked.id()), refunds.ofCharge(locked.id())));
        if (charge.hasReceived(credit.endToEndId())) {
            LOG.info(
                    "Pix credit {} was already recorded as a payment of charge {}",
                    credit.endToEndId(),
                    charge.id().publicId());
            return CreditResult.ALREADY_RECORDED;
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var incoming = new IncomingPayment(
                new PaymentId(ids.next()), credit.endToEndId(), credit.amount(), credit.acceptedAt());
        Receipt receipt = charge.receive(incoming, now);
        EntryId entry = post(tenant, credit);
        roles.call(ROLE, () -> {
            payments.insert(tenant.id(), charge.id(), receipt.payment(), entry, now);
            if (receipt.charge().status() != charge.status()) {
                charges.updateStatus(
                        charge.id(),
                        receipt.charge().status(),
                        receipt.charge().paidAt().orElse(null),
                        now);
            }
            return null;
        });
        observeOnCommit(receipt, credit, now);
        return CreditResult.RECORDED;
    }

    // Logged and counted once the transaction commits: a payment rolled back was never recorded,
    // and the check of the ledger's balances at commit can still roll it back.
    private void observeOnCommit(Receipt receipt, PixCredit credit, Instant settledAt) {
        Payment payment = receipt.payment();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                log(receipt, credit);
                Objects.requireNonNull(recorded.get(
                                tags(payment.outcome(), payment.reviewReason().orElse(null))))
                        .increment();
                // The rails' clock may be slightly ahead of the core's.
                Duration waited = Duration.between(payment.acceptedAt(), settledAt);
                lag.record(waited.isNegative() ? Duration.ZERO : waited);
            }
        });
    }

    private static Counter payments(MeterRegistry meters, PaymentOutcome outcome, @Nullable ReviewReason reason) {
        return Counter.builder(PAYMENTS)
                .description("Payments recorded, by what they did to their charge")
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .tag("reason", reason == null ? "none" : reason.name().toLowerCase(Locale.ROOT))
                .register(meters);
    }

    private static String tags(PaymentOutcome outcome, @Nullable ReviewReason reason) {
        return outcome + "/" + reason;
    }

    // Settled or flagged, the money is in Finaxis's settlement account, so it is owed to the tenant
    // (section 8.3). The EndToEndId is the source, so the ledger refuses a second entry for it.
    private EntryId post(TenantView tenant, PixCredit credit) {
        AccountId available = ledger.openAccount(BillingService.availableAccount(tenant));
        AccountId settlement = platform.settlement(tenant.environment());
        var request = new EntryRequest(
                tenant.id().value(),
                new EntrySource("pix_credit", credit.endToEndId(), "settlement"),
                BusinessCalendar.businessDateOf(credit.acceptedAt()),
                credit.messageId(),
                List.of(
                        PostingRequest.debit(settlement, credit.amount()),
                        PostingRequest.credit(available, credit.amount())));
        return switch (ledger.post(request)) {
            case PostResult.Posted(EntryId entry) -> entry;
            case PostResult.AlreadyPosted already ->
                throw new IllegalStateException(
                        "Pix credit " + credit.endToEndId() + " was posted without its payment");
            case PostResult.InsufficientFunds shortOf ->
                throw new IllegalStateException("A credit to the tenant found account "
                        + shortOf.account().value() + " short");
        };
    }

    private static void log(Receipt receipt, PixCredit credit) {
        Payment payment = receipt.payment();
        if (payment.isSettled()) {
            LOG.info(
                    "Pix credit {} of message {} settled charge {}",
                    credit.endToEndId(),
                    credit.messageId(),
                    receipt.charge().id().publicId());
        } else {
            LOG.warn(
                    "Pix credit {} of message {} was credited and flagged {} on charge {}",
                    credit.endToEndId(),
                    credit.messageId(),
                    payment.reviewReason().orElseThrow(),
                    receipt.charge().id().publicId());
        }
    }
}
