package dev.finaxis.core.shared;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.stream.IntStream;

/**
 * An amount of money: a whole number of the currency's minor units (centavos for BRL) and the
 * currency.
 *
 * <p>Amounts are never held as floating point or as decimal major units: {@code long} minor units
 * are exact, match the {@code BIGINT} columns in the database and compare with {@code equals}. A
 * {@code Money} may be negative or zero, for example a balance or a difference; a rule that needs a
 * positive amount, such as a ledger posting, checks it where the rule lives.
 *
 * @param minorUnits the amount in the currency's smallest unit; {@code 10050} is R$ 100.50 in BRL
 * @param currency the ISO 4217 currency; it must have a minor unit
 */
public record Money(long minorUnits, Currency currency) {

    /** Rejects currencies without a minor unit, such as gold ({@code XAU}), which cannot be counted. */
    public Money {
        if (currency.getDefaultFractionDigits() < 0) {
            throw new IllegalArgumentException("Currency " + currency + " has no minor unit");
        }
    }

    /** Money from an amount already in minor units: {@code ofMinor(10050, BRL)} is R$ 100.50. */
    public static Money ofMinor(long minorUnits, Currency currency) {
        return new Money(minorUnits, currency);
    }

    /** Zero in the given currency. */
    public static Money zero(Currency currency) {
        return new Money(0, currency);
    }

    /**
     * Money from an amount in major units: {@code of(new BigDecimal("100.50"), BRL)} is R$ 100.50.
     *
     * <p>The amount must fit the currency exactly. R$ 10.005 is rejected rather than rounded, because
     * silently changing an amount a caller asked for is a bug.
     *
     * @throws IllegalArgumentException if the amount has more decimal places than the currency allows
     *     or does not fit in a {@code long} of minor units
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

    /** The amount in major units, with the currency's decimal places: R$ 100.50 is {@code 100.50}. */
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
     * This amount plus another in the same currency.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException if the result does not fit in a {@code long}, instead of wrapping
     *     around to a wrong amount
     */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * This amount minus another in the same currency; the result may be negative.
     *
     * @throws IllegalArgumentException if the currencies differ
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minorUnits, other.minorUnits), currency);
    }

    /**
     * The same amount with the opposite sign.
     *
     * @throws ArithmeticException for {@code Long.MIN_VALUE} minor units, which has no positive
     *     counterpart
     */
    public Money negate() {
        return new Money(Math.negateExact(minorUnits), currency);
    }

    /**
     * This amount multiplied by a rate, such as a 2.99% fee ({@code 0.0299}), rounded to whole minor
     * units with {@link RoundingMode#HALF_EVEN}.
     *
     * <p>This is the only operation that rounds. Rates are {@code BigDecimal}, never {@code double},
     * so {@code 0.0299} is exactly that number. HALF_EVEN (banker's rounding) sends an exact half to
     * the nearest even number: 0.5 and 2.5 centavos round down, 1.5 rounds up. Over many operations
     * the halves rounded up and down balance out, while HALF_UP would always round them up and
     * drift in one direction.
     *
     * @throws ArithmeticException if the result does not fit in a {@code long}
     */
    public Money times(BigDecimal rate) {
        BigDecimal exact = BigDecimal.valueOf(minorUnits).multiply(rate);
        return new Money(exact.setScale(0, RoundingMode.HALF_EVEN).longValueExact(), currency);
    }

    /**
     * Splits this amount into parts proportional to the ratios, without losing or creating a minor
     * unit: the parts always add up to this amount. {@code allocate(70, 27, 3)} of R$ 100.00 is R$
     * 70.00, R$ 27.00 and R$ 3.00; {@code allocate(1, 1, 1)} of R$ 1.00 is R$ 0.34, R$ 0.33 and R$
     * 0.33.
     *
     * <p>Rounding each share with {@link #times} could leave the parts one centavo short of, or over,
     * the total. Instead each part first gets its exact share rounded down, and the centavos left over
     * go one each to the parts with the largest discarded fractions (the largest remainder method);
     * on a tie, the earlier part wins. Every part is then within one minor unit of its exact share. A
     * negative amount is split as its absolute value and negated, so the result mirrors the positive
     * one.
     *
     * @param ratios the weight of each part, in order; none negative, at least one positive
     * @return one part per ratio, in the order of the ratios
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

    /**
     * Adding reais to dollars has no meaning; converting needs a rate and is a business decision, never
     * an implicit step. Mixing currencies is a programming error, so it is an exception, not a result.
     */
    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot combine " + currency + " with " + other.currency);
        }
    }

    /** The currency code and the amount in major units, such as {@code BRL 100.50}, for logs and tests. */
    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toMajorUnits().toPlainString();
    }
}
