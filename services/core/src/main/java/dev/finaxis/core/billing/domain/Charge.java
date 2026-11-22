package dev.finaxis.core.billing.domain;

import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;

/**
 * A tenant's request to be paid an amount by Pix before a time (billing design, section 3). Never
 * changed in place: each transition returns the charge as it is afterwards, and the constructor
 * refuses any state the transitions could not reach.
 */
public record Charge(ChargeId id, Money amount, Instant createdAt, Instant expiresAt, ChargeStatus status) {

    /** The only currency of Pix. */
    public static final Currency CURRENCY = Currency.getInstance("BRL");

    /** R$ 1,000,000.00; each tenant's own limits are lower, and are risk's. */
    public static final long MAX_AMOUNT_MINOR = 100_000_000L;

    /** Keeps the edge of expiry, where the two clocks may disagree, a small part of a charge's life. */
    public static final Duration MIN_LIFETIME = Duration.ofMinutes(1);

    public Charge {
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
    }

    /** A new charge, waiting for its payment. */
    public static Charge create(ChargeId id, Money amount, Instant createdAt, Instant expiresAt) {
        return new Charge(id, amount, createdAt, expiresAt, ChargeStatus.ACTIVE);
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

    private Charge withStatus(ChargeStatus newStatus) {
        return new Charge(id, amount, createdAt, expiresAt, newStatus);
    }
}
