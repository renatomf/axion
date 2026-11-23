package dev.axion.core.shared;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.stream.IntStream;

/**
 * An amount in the currency's minor units (centavos for BRL): {@code long} minor units are exact and
 * match the database's {@code BIGINT}. It may be negative or zero; a rule that needs a positive
 * amount checks it where the rule lives.
 *
 * @param currency ISO 4217, with a minor unit
 */
// Written as its two components only, so domain events read back: isZero() and its kind are not state.
@JsonAutoDetect(isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public record Money(long minorUnits, Currency currency) {

    // A currency without a minor unit, such as gold (XAU), cannot be counted.
    public Money {
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency " + currency + " has no minor unit");
        }
    }

    /** {@code ofMinor(10050, BRL)} is R$ 100.50. */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * {@code of(new BigDecimal("100.50"), BRL)} is R$ 100.50. R$ 10.005 is rejected, not rounded:
     * silently changing a requested amount is a bug.
     *
     * @throws IllegalArgumentException if the amount does not fit the currency exactly
     */
    public static Money of(BigDecimal amount, Currency currency) {
        BigDecimal minorUnits = amount.movePointRight(currency.getDefaultFractionDigits());
        try {
            return new Money(minorUnits.longValueExact(), currency);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount + " is not a whole number of " + currency + " minor units, or is out of range",
                    e);
        }
    }

    public BigDecimal toMajorUnits() {
        return BigDecimal.valueOf(minorUnits, currency.getDefaultFractionDigits());
    }

    public boolean isZero() {
        return minorUnits == 0;
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    public boolean isNegative() {
        return minorUnits < 0;
    }

    /**
     * The sum, in the same currency.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException on overflow, instead of wrapping around to a wrong amount
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * The difference, in the same currency; it may be negative.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException on overflow
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * The opposite sign.
     *
     * @throws ArithmeticException for {@code Long.MIN_VALUE} minor units, which has no positive counterpart
     */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    /**
     * Multiplied by a rate such as a 2.99% fee, the only operation that rounds: {@link
     * RoundingMode#HALF_EVEN}, so over many operations the halves balance out instead of drifting up.
     *
     * @throws ArithmeticException on overflow
     */
    public Money times(BigDecimal rate) {
        BigDecimal exact = BigDecimal.valueOf(minorUnits).multiply(rate);
        return new Money(exact.setScale(0, RoundingMode.HALF_EVEN).longValueExact(), currency);
    }

    /**
     * Splits into parts proportional to the ratios that always add up to this amount: {@code
     * allocate(1, 1, 1)} of R$ 1.00 is R$ 0.34, R$ 0.33 and R$ 0.33. Largest remainder method; on a
     * tie, the earlier part wins.
     *
     * @throws IllegalArgumentException if there are no ratios, one is negative, or all are zero
     */
    public List<Money> allocate(long... ratios) {
        if (ratios.length == 0) {
            throw new IllegalArgumentException("At least one ratio is required");
        }
        BigInteger ratioTotal = BigInteger.ZERO;
        for (long ratio : ratios) {
            if (ratio < 0) {
                throw new IllegalArgumentException("Ratios must not be negative: " + ratio);
            }
            ratioTotal = ratioTotal.add(BigInteger.valueOf(ratio));
        }
        if (ratioTotal.signum() == 0) {
            throw new IllegalArgumentException("At least one ratio must be positive");
        }

        // BigInteger: amount × ratio may not fit in a long, even when each share does, and neither
        // does the absolute value of Long.MIN_VALUE.
        BigInteger amount = BigInteger.valueOf(minorUnits).abs();
        BigInteger[] shares = new BigInteger[ratios.length];
        BigInteger[] remainders = new BigInteger[ratios.length];
        BigInteger allocated = BigInteger.ZERO;
        for (int i = 0; i < ratios.length; i++) {
            BigInteger[] division =
                    amount.multiply(BigInteger.valueOf(ratios[i])).divideAndRemainder(ratioTotal);
            shares[i] = division[0];
            remainders[i] = division[1];
            allocated = allocated.add(division[0]);
        }

        // Fewer centavos are left over than there are parts, so this hands out at most one each.
        int leftOver = amount.subtract(allocated).intValueExact();
        List<Integer> byLargestRemainder = IntStream.range(0, ratios.length)
                .boxed()
                .sorted(Comparator.comparing((Integer i) -> remainders[i]).reversed())
                .toList();
        for (int i : byLargestRemainder.subList(0, leftOver)) {
            shares[i] = shares[i].add(BigInteger.ONE);
        }

        // The sign goes back before leaving BigInteger: a share of -2^63 fits a long, +2^63 does not.
        BigInteger sign = BigInteger.valueOf(Long.signum(minorUnits));
        return Arrays.stream(shares)
                .map(share -> new Money(share.multiply(sign).longValueExact(), currency))
                .toList();
    }

    // Converting needs a rate and is a business decision, never an implicit step.
    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot combine " + currency + " with " + other.currency);
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toMajorUnits().toPlainString();
    }
}
