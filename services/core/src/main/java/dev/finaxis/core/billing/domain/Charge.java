package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeStatus;
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
        ChargeId id, Money amount, Instant createdAt, Instant expiresAt, ChargeStatus status, List<Payment> payments) {

    /** The only currency of Pix. */
    public static final Currency CURRENCY = Currency.getInstance("BRL");

    /** R$ 1,000,000.00; each tenant's own limits are lower, and are risk's. */
    public static final long MAX_AMOUNT_MINOR = 100_000_000L;

    /** Keeps the edge of expiry, where the two clocks may disagree, a small part of a charge's life. */
    public static final Duration MIN_LIFETIME = Duration.ofMinutes(1);

    public Charge {
        payments = List.copyOf(payments);
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
    }

    /** A new charge, waiting for its payment. */
    public static Charge create(ChargeId id, Money amount, Instant createdAt, Instant expiresAt) {
        return new Charge(id, amount, createdAt, expiresAt, ChargeStatus.ACTIVE, List.of());
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
        return withStatus(ChargeStatus.EXPIRED);
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

    /** The payment that settled the charge, if one did. */
    public Optional<Payment> settlingPayment() {
        return payments.stream().filter(Payment::isSettled).findFirst();
    }

    /** When the rails accepted the settling payment. */
    public Optional<Instant> paidAt() {
        return settlingPayment().map(Payment::acceptedAt);
    }

    private Receipt decide(IncomingPayment incoming) {
        if (isPaid()) {
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

    private boolean isPaid() {
        return status == ChargeStatus.PAID;
    }

    private Receipt record(Payment payment, ChargeStatus newStatus) {
        List<Payment> received = new ArrayList<>(payments);
        received.add(payment);
        return new Receipt(new Charge(id, amount, createdAt, expiresAt, newStatus, received), payment);
    }

    private Charge withStatus(ChargeStatus newStatus) {
        return new Charge(id, amount, createdAt, expiresAt, newStatus, payments);
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
        boolean paid = status == ChargeStatus.PAID;
        if (paid != !settling.isEmpty()) {
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
}
