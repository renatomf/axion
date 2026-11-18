package dev.finaxis.core.shared;

import java.math.BigDecimal;
import java.util.Currency;

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

    /** The currency code and the amount in major units, such as {@code BRL 100.50}, for logs and tests. */
    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + toMajorUnits().toPlainString();
    }
}
