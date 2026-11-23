package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/** Properties of every {@code Money}, checked by jqwik against generated inputs. */
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

    /** Allocation never loses or creates a minor unit, over the whole {@code long} range. */
    @Property(tries = 1000)
    void allocatedPartsAddUpToTheAmount(@ForAll long minorUnits, @ForAll("ratios") long[] ratios) {
        Money money = Money.ofMinor(minorUnits, BRL);

        List<Money> parts = money.allocate(ratios);

        assertThat(parts).hasSize(ratios.length);
        assertThat(parts.stream().reduce(Money.zero(BRL), Money::plus)).isEqualTo(money);
    }

    /**
     * Each part is less than one minor unit away from its exact share, {@code amount × ratio / total}.
     * Multiplied out to stay in whole numbers: {@code |part × total − amount × ratio| < total}.
     */
    @Property(tries = 1000)
    void eachPartIsWithinOneMinorUnitOfItsExactShare(@ForAll long minorUnits, @ForAll("ratios") long[] ratios) {
        List<Money> parts = Money.ofMinor(minorUnits, BRL).allocate(ratios);
        BigInteger total = Arrays.stream(ratios).mapToObj(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);

        for (int i = 0; i < ratios.length; i++) {
            BigInteger exactTimesTotal = BigInteger.valueOf(minorUnits).multiply(BigInteger.valueOf(ratios[i]));
            BigInteger distance = BigInteger.valueOf(parts.get(i).minorUnits())
                    .multiply(total)
                    .subtract(exactTimesTotal)
                    .abs();
            assertThat(distance).isLessThan(total);
        }
    }

    /** Ratios of up to a billion, so that amount × ratio overflows a {@code long}, with a positive total. */
    @Provide
    Arbitrary<long[]> ratios() {
        return Arbitraries.longs()
                .between(0, 1_000_000_000)
                .array(long[].class)
                .ofMinSize(1)
                .ofMaxSize(10)
                .filter(ratios -> Arrays.stream(ratios).anyMatch(ratio -> ratio > 0));
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
