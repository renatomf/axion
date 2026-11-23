package dev.axion.core.billing.application;

import dev.axion.core.ModuleRoles;
import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.PaymentOutcome;
import dev.axion.core.billing.PaymentView;
import dev.axion.core.billing.ReviewReason;
import dev.axion.core.billing.domain.Charge;
import dev.axion.core.billing.domain.IncomingPayment;
import dev.axion.core.billing.domain.Payment;
import dev.axion.core.billing.domain.Receipt;
import dev.axion.core.billing.domain.TransactionId;
import dev.axion.core.billing.persistence.JdbcCharges;
import dev.axion.core.billing.persistence.JdbcCharges.LockedCharge;
import dev.axion.core.billing.persistence.JdbcPayments;
import dev.axion.core.billing.persistence.JdbcRefunds;
import dev.axion.core.ledger.AccountId;
import dev.axion.core.ledger.EntryId;
import dev.axion.core.ledger.EntryRequest;
import dev.axion.core.ledger.EntrySource;
import dev.axion.core.ledger.LedgerApi;
import dev.axion.core.ledger.PostResult;
import dev.axion.core.ledger.PostingRequest;
import dev.axion.core.rails.CreditResult;
import dev.axion.core.rails.PixCredit;
import dev.axion.core.rails.PixCreditHandler;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ActorContext;
import dev.axion.core.shared.BusinessCalendar;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantContext;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.tenancy.TenantView;
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
import org.springframework.context.ApplicationEventPublisher;
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

    public static final String PAYMENTS = "axion.billing.payments";

    public static final String SETTLEMENT_LAG = "axion.billing.settlement.lag";

    private static final String ROLE = "axion_billing";

    private static final Actor ACTOR = new Actor.Job("pix-settlement");

    private static final Logger LOG = LoggerFactory.getLogger(Settlement.class);

    private final JdbcCharges charges;
    private final JdbcPayments payments;
    private final JdbcRefunds refunds;
    private final ChargeDetails details;
    private final LedgerApi ledger;
    private final TenancyApi tenancy;
    private final PlatformAccounts platform;
    private final ModuleRoles roles;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final UuidV7Generator ids;
    private final Map<String, Counter> recorded = new HashMap<>();
    private final Timer lag;

    public Settlement(
            JdbcCharges charges,
            JdbcPayments payments,
            JdbcRefunds refunds,
            ChargeDetails details,
            LedgerApi ledger,
            TenancyApi tenancy,
            PlatformAccounts platform,
            ModuleRoles roles,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            Clock clock,
            MeterRegistry meters) {
        this.charges = charges;
        this.payments = payments;
        this.refunds = refunds;
        this.details = details;
        this.ledger = ledger;
        this.tenancy = tenancy;
        this.platform = platform;
        this.roles = roles;
        this.transactions = transactions;
        this.events = events;
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
     * with that tenant bound (section 8.1). Its events name the settlement job, with the rails'
     * message as their correlation ID.
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
                () -> ActorContext.call(
                        ACTOR,
                        credit.messageId(),
                        () -> Objects.requireNonNull(
                                transactions.execute(status -> settle(tenant, transactionId, credit)))));
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
        publish(tenant, charge, receipt, now);
        observeOnCommit(receipt, credit, now);
        return CreditResult.RECORDED;
    }

    // After the role switch returns: the registry is written with the login role (events and
    // webhooks design, section 4.2). A charge settlement found due expires first.
    private void publish(TenantView tenant, Charge before, Receipt receipt, Instant now) {
        var binding = new TenantContext.Binding(tenant.id(), tenant.environment());
        ChargeDetail after = roles.call(ROLE, () -> details.find(binding, before.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Charge " + before.id().publicId() + " vanished while it was settled"));
        if (before.status() == ChargeStatus.ACTIVE && receipt.charge().status() == ChargeStatus.EXPIRED) {
            events.publishEvent(new ChargeExpired(header(now), after));
        }
        Payment payment = receipt.payment();
        if (payment.isSettled()) {
            events.publishEvent(new ChargePaid(header(now), after));
        } else {
            events.publishEvent(new PaymentFlagged(header(now), view(payment), after));
        }
    }

    private EventHeader header(Instant now) {
        return EventHeader.ofCurrentWork(new EventId(ids.next()), now);
    }

    // A payment just recorded: nothing of it has been refunded.
    private static PaymentView view(Payment payment) {
        return new PaymentView(
                payment.id(),
                payment.endToEndId(),
                payment.amount(),
                Money.zero(Charge.CURRENCY),
                payment.acceptedAt(),
                payment.outcome(),
                payment.reviewReason());
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

    // Settled or flagged, the money is in Axion's settlement account, so it is owed to the tenant
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
