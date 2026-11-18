package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Currency;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Properties that hold for every {@code Money}, checked by jqwik against generated inputs instead of
 * hand-picked examples.
 *
 * <p>Each {@code @Property} runs 1,000 times with new values; generation favors edge cases such as
 * zero, one and the limits of the range. When a property fails, jqwik shrinks the input to the
 * smallest one that still fails and reports it, together with the seed that reproduces the run.
 */
class MoneyPropertiesTest {

    private static final Currency BRL = Currency.getInstance("BRL");

    /**
     * A bound on generated amounts, so that sums of three of them cannot overflow: about R$ 10
     * trillion, far beyond any real balance. Overflow itself is covered by its own property.
     */
    private static final long LIMIT = 1_000_000_000_000_000L;

    @Property(tries = 1000)
    void additionIsAssociative(@ForAll("amounts") Money a, @ForAll("amounts") Money b, @ForAll("amounts") Money c) {
        assertThat(a.plus(b).plus(c)).isEqualTo(a.plus(b.plus(c)));
    }

    @Property(tries = 1000)
    void additionIsCommutative(@ForAll("amounts") Money a, @ForAll("amounts") Money b) {
        assertThat(a.plus(b)).isEqualTo(b.plus(a));
    }

    @Property(tries = 1000)
    void zeroIsTheIdentity(@ForAll("amounts") Money a) {
        assertThat(a.plus(Money.zero(BRL))).isEqualTo(a);
    }

    @Property(tries = 1000)
    void subtractionUndoesAddition(@ForAll("amounts") Money a, @ForAll("amounts") Money b) {
        assertThat(a.plus(b).minus(b)).isEqualTo(a);
        assertThat(a.minus(b)).isEqualTo(a.plus(b.negate()));
    }

    /**
     * Over the whole {@code long} range, a sum is either exact or an exception: compared with
     * {@link BigInteger}, which never overflows, it never wraps around to a wrong amount.
     */
    @Property(tries = 1000)
    void additionIsExactOrFails(@ForAll long a, @ForAll long b) {
        BigInteger exact = BigInteger.valueOf(a).add(BigInteger.valueOf(b));
        Money left = Money.ofMinor(a, BRL);
        Money right = Money.ofMinor(b, BRL);

        if (exact.bitLength() < Long.SIZE) {
            assertThat(left.plus(right).minorUnits()).isEqualTo(exact.longValueExact());
        } else {
            assertThatThrownBy(() -> left.plus(right)).isInstanceOf(ArithmeticException.class);
        }
    }

    /** Converting to major units and back loses nothing, whatever the currency's decimal places. */
    @Property(tries = 1000)
    void majorUnitsRoundTripWithoutLoss(@ForAll long minorUnits, @ForAll("currencies") Currency currency) {
        Money money = Money.ofMinor(minorUnits, currency);

        assertThat(Money.of(money.toMajorUnits(), currency)).isEqualTo(money);
    }

    /** Applying a rate rounds once: the result is never more than half a minor unit from the exact value. */
    @Property(tries = 1000)
    void rateIsAppliedWithinHalfAMinorUnit(@ForAll("amounts") Money money, @ForAll("rates") BigDecimal rate) {
        BigDecimal exact = BigDecimal.valueOf(money.minorUnits()).multiply(rate);
        BigDecimal error = BigDecimal.valueOf(money.times(rate).minorUnits())
                .subtract(exact)
                .abs();

        assertThat(error).isLessThanOrEqualTo(new BigDecimal("0.5"));
    }

    @Provide
    Arbitrary<Money> amounts() {
        return Arbitraries.longs().between(-LIMIT, LIMIT).map(minorUnits -> Money.ofMinor(minorUnits, BRL));
    }

    /** Currencies with zero (yen), two (real) and three (Bahraini dinar) decimal places. */
    @Provide
    Arbitrary<Currency> currencies() {
        return Arbitraries.of("JPY", "BRL", "BHD").map(Currency::getInstance);
    }

    /** Rates from 0% to 100% with up to six decimal places, such as fees and split percentages. */
    @Provide
    Arbitrary<BigDecimal> rates() {
        return Arbitraries.longs().between(0, 1_000_000).map(unscaled -> BigDecimal.valueOf(unscaled, 6));
    }
}
