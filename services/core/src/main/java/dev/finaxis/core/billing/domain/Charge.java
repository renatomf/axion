package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.RefundId;
import dev.finaxis.core.billing.RefundRefusal;
import dev.finaxis.core.billing.ReviewReason;
import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

/**
 * A tenant's request to be paid an amount by Pix before a time (billing design, section 3). Never
 * changed in place: each transition returns the charge as it is afterwards, and the constructor
 * refuses any state the transitions could not reach.
 */
public record Charge(
        ChargeId id,
        Money amount,
        Instant createdAt,
        Instant expiresAt,
        ChargeStatus status,
        List<Payment> payments,
        List<Refund> refunds) {

    /** The only currency of Pix. */
    public static final Currency CURRENCY = Currency.getInstance("BRL");

    /** R$ 1,000,000.00; each tenant's own limits are lower, and are risk's. */
    public static final long MAX_AMOUNT_MINOR = 100_000_000L;

    /** Keeps the edge of expiry, where the two clocks may disagree, a small part of a charge's life. */
    public static final Duration MIN_LIFETIME = Duration.ofMinutes(1);

    /** The period the Pix rules give a receiver to return a payment. */
    public static final Duration REFUND_WINDOW = Duration.ofDays(90);

    /** Bounds a charge's detail and the work of each refund. */
    public static final int MAX_REFUNDS = 50;

    public Charge {
        payments = List.copyOf(payments);
        refunds = List.copyOf(refunds);
        if (!amount.currency().equals(CURRENCY)) {
            throw new IllegalArgumentException("A charge is in " + CURRENCY + ", not " + amount.currency());
        }
        if (!amount.isPositive() || amount.minorUnits() > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException(
                    "A charge's amount is 1 to " + MAX_AMOUNT_MINOR + " minor units, not " + amount.minorUnits());
        }
        if (expiresAt.isBefore(createdAt.plus(MIN_LIFETIME))) {
            throw new IllegalArgumentException(
                    "A charge expires at least " + MIN_LIFETIME + " after it is created, not at " + expiresAt);
        }
        requireConsistentPayments(amount, expiresAt, status, payments);
        requireConsistentRefunds(amount, status, payments, refunds);
    }

    /** A new charge, waiting for its payment. */
    public static Charge create(ChargeId id, Money amount, Instant createdAt, Instant expiresAt) {
        return new Charge(id, amount, createdAt, expiresAt, ChargeStatus.ACTIVE, List.of(), List.of());
    }

    /**
     * The charge, expired.
     *
     * @throws IllegalStateException if it is not active or its expiry has not passed, a bug in the caller
     */
    public Charge expire(Instant now) {
        if (status != ChargeStatus.ACTIVE || now.isBefore(expiresAt)) {
            throw new IllegalStateException("Charge " + id.value() + " cannot expire: " + status + " at " + now);
        }
        return new Charge(id, amount, createdAt, expiresAt, ChargeStatus.EXPIRED, payments, refunds);
    }

    /**
     * Records a payment: it settles the charge if it is of the charge's amount and the rails accepted
     * it before the expiry, and is flagged otherwise (billing design, section 8.2). A charge whose
     * expiry has passed by {@code now} is expired first.
     *
     * @throws IllegalArgumentException if the charge already has a payment with its EndToEndId, a bug in the caller
     */
    public Receipt receive(IncomingPayment incoming, Instant now) {
        boolean known = payments.stream().anyMatch(p -> p.endToEndId().equals(incoming.endToEndId()));
        if (known) {
            throw new IllegalArgumentException(
                    "Charge " + id.value() + " already has payment " + incoming.endToEndId());
        }
        Charge charge = status == ChargeStatus.ACTIVE && !now.isBefore(expiresAt) ? expire(now) : this;
        return charge.decide(incoming);
    }

    /**
     * Refunds a payment of the charge (billing design, section 10.2). The payment may be omitted when
     * the charge has only one; the amount, to return all that remains of it. Whether the tenant's
     * balance covers it is the ledger's to decide.
     *
     * @throws IllegalArgumentException if the amount is not positive or not in the charge's currency, a bug in the caller
     */
    public RefundDecision refund(
            RefundId refundId, Optional<PaymentId> paymentId, Optional<Money> requested, Instant now) {
        requested.ifPresent(value -> {
            if (!value.isPositive() || !value.currency().equals(CURRENCY)) {
                throw new IllegalArgumentException("A refund is a positive amount in " + CURRENCY + ", not " + value);
            }
        });
        if (payments.isEmpty()) {
            return new RefundDecision.Refused(RefundRefusal.CHARGE_NOT_PAID);
        }
        if (paymentId.isEmpty() && payments.size() > 1) {
            return new RefundDecision.Refused(RefundRefusal.PAYMENT_REQUIRED);
        }
        Optional<Payment> found = paymentId.isPresent() ? payment(paymentId.get()) : Optional.of(payments.getFirst());
        if (found.isEmpty()) {
            return new RefundDecision.Refused(RefundRefusal.UNKNOWN_PAYMENT);
        }
        Payment payment = found.get();
        Money remaining = payment.amount().minus(refundedFrom(payment.id()));
        Money refundAmount = requested.orElse(remaining);
        if (!refundAmount.isPositive() || refundAmount.minorUnits() > remaining.minorUnits()) {
            return new RefundDecision.Refused(RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE);
        }
        if (now.isAfter(payment.acceptedAt().plus(REFUND_WINDOW))) {
            return new RefundDecision.Refused(RefundRefusal.REFUND_WINDOW_CLOSED);
        }
        if (refunds.size() >= MAX_REFUNDS) {
            return new RefundDecision.Refused(RefundRefusal.REFUND_LIMIT_REACHED);
        }

        Refund refund = new Refund(refundId, payment.id(), refundAmount, now);
        List<Refund> withRefund = new ArrayList<>(refunds);
        withRefund.add(refund);
        ChargeStatus newStatus = payment.isSettled()
                ? (refundAmount.equals(remaining) ? ChargeStatus.REFUNDED : ChargeStatus.PARTIALLY_REFUNDED)
                : status;
        return new RefundDecision.Refunded(
                new Charge(id, amount, createdAt, expiresAt, newStatus, payments, withRefund), refund);
    }

    /** The payment that settled the charge, if one did. */
    public Optional<Payment> settlingPayment() {
        return payments.stream().filter(Payment::isSettled).findFirst();
    }

    /** When the rails accepted the settling payment. */
    public Optional<Instant> paidAt() {
        return settlingPayment().map(Payment::acceptedAt);
    }

    /** What refunds returned of the settling payment; refunds of flagged payments are not counted. */
    public Money amountRefunded() {
        return settlingPayment().map(p -> refundedFrom(p.id())).orElse(Money.zero(CURRENCY));
    }

    /** What refunds returned of one payment. */
    public Money refundedFrom(PaymentId payment) {
        return sumOfRefunds(refunds, payment);
    }

    private Optional<Payment> payment(PaymentId paymentId) {
        return payments.stream().filter(p -> p.id().equals(paymentId)).findFirst();
    }

    private Receipt decide(IncomingPayment incoming) {
        if (status.isPaid()) {
            return record(Payment.flagged(incoming, ReviewReason.CHARGE_ALREADY_PAID), status);
        }
        if (!incoming.acceptedAt().isBefore(expiresAt)) {
            return record(Payment.flagged(incoming, ReviewReason.CHARGE_EXPIRED), ChargeStatus.EXPIRED);
        }
        if (!incoming.amount().equals(amount)) {
            return record(Payment.flagged(incoming, ReviewReason.AMOUNT_MISMATCH), status);
        }
        return record(Payment.settled(incoming), ChargeStatus.PAID);
    }

    private Receipt record(Payment payment, ChargeStatus newStatus) {
        List<Payment> received = new ArrayList<>(payments);
        received.add(payment);
        return new Receipt(new Charge(id, amount, createdAt, expiresAt, newStatus, received, refunds), payment);
    }

    private static Money sumOfRefunds(List<Refund> refunds, PaymentId payment) {
        return refunds.stream()
                .filter(r -> r.payment().equals(payment))
                .map(Refund::amount)
                .reduce(Money.zero(CURRENCY), Money::plus);
    }

    private static void requireConsistentPayments(
            Money amount, Instant expiresAt, ChargeStatus status, List<Payment> payments) {
        var endToEndIds = new HashSet<String>();
        for (Payment payment : payments) {
            if (!endToEndIds.add(payment.endToEndId())) {
                throw new IllegalArgumentException("Payment " + payment.endToEndId() + " appears twice");
            }
        }
        List<Payment> settling = payments.stream().filter(Payment::isSettled).toList();
        if (settling.size() > 1) {
            throw new IllegalArgumentException("A charge has at most one settling payment, not " + settling.size());
        }
        if (status.isPaid() != !settling.isEmpty()) {
            throw new IllegalArgumentException(
                    "A " + status + " charge cannot have " + settling.size() + " settling payments");
        }
        for (Payment payment : settling) {
            if (!payment.amount().equals(amount) || !payment.acceptedAt().isBefore(expiresAt)) {
                throw new IllegalArgumentException("Payment " + payment.endToEndId() + " cannot settle a charge of "
                        + amount + " expiring at " + expiresAt);
            }
        }
    }

    private static void requireConsistentRefunds(
            Money amount, ChargeStatus status, List<Payment> payments, List<Refund> refunds) {
        if (refunds.size() > MAX_REFUNDS) {
            throw new IllegalArgumentException(
                    "A charge has at most " + MAX_REFUNDS + " refunds, not " + refunds.size());
        }
        var paymentIds = new HashSet<PaymentId>();
        payments.forEach(p -> paymentIds.add(p.id()));
        for (Refund refund : refunds) {
            if (!paymentIds.contains(refund.payment())) {
                throw new IllegalArgumentException("Refund " + refund.id().value() + " is of no payment of the charge");
            }
        }
        for (Payment payment : payments) {
            Money refunded = sumOfRefunds(refunds, payment.id());
            if (refunded.minorUnits() > payment.amount().minorUnits()) {
                throw new IllegalArgumentException("Refunds of " + payment.endToEndId() + " return " + refunded
                        + ", more than its " + payment.amount());
            }
            if (payment.isSettled()) {
                ChargeStatus expected = refunded.isZero()
                        ? ChargeStatus.PAID
                        : refunded.equals(amount) ? ChargeStatus.REFUNDED : ChargeStatus.PARTIALLY_REFUNDED;
                if (status != expected) {
                    throw new IllegalArgumentException(
                            "A charge whose payment was refunded " + refunded + " is " + expected + ", not " + status);
                }
            }
        }
    }
}
