package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency JPY = Currency.getInstance("JPY");

    @Test
    void holdsMinorUnitsAndCurrency() {
        Money money = Money.ofMinor(10050, BRL);

        assertThat(money.minorUnits()).isEqualTo(10050);
        assertThat(money.currency()).isEqualTo(BRL);
        assertThat(money.toMajorUnits()).isEqualTo(new BigDecimal("100.50"));
        assertThat(money).hasToString("BRL 100.50");
    }

    @Test
    void convertsMajorUnitsUsingTheCurrencysDecimalPlaces() {
        assertThat(Money.of(new BigDecimal("100.5"), BRL)).isEqualTo(Money.ofMinor(10050, BRL));
        assertThat(Money.of(new BigDecimal("100"), JPY)).isEqualTo(Money.ofMinor(100, JPY));
    }

    @Test
    void equalAmountsAreEqualWhateverTheirScale() {
        assertThat(Money.of(new BigDecimal("1.0"), BRL)).isEqualTo(Money.of(new BigDecimal("1.00"), BRL));
    }

    @Test
    void sameAmountInDifferentCurrenciesIsNotEqual() {
        assertThat(Money.ofMinor(100, BRL)).isNotEqualTo(Money.ofMinor(100, Currency.getInstance("USD")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.005", "0.001", "99999999999999999999"})
    void rejectsAmountsThatDoNotFitTheCurrency(String amount) {
        assertThatThrownBy(() -> Money.of(new BigDecimal(amount), BRL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(amount);
    }

    @Test
    void rejectsCurrenciesWithoutMinorUnits() {
        assertThatThrownBy(() -> Money.zero(Currency.getInstance("XAU")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("XAU");
    }

    @Test
    void reportsItsSign() {
        assertThat(Money.zero(BRL).isZero()).isTrue();
        assertThat(Money.ofMinor(1, BRL).isPositive()).isTrue();
        assertThat(Money.ofMinor(-1, BRL).isNegative()).isTrue();
        assertThat(Money.ofMinor(-1, BRL).isPositive()).isFalse();
    }

    @Test
    void addsAndSubtractsInTheSameCurrency() {
        Money hundred = Money.ofMinor(10000, BRL);
        Money thirty = Money.ofMinor(3000, BRL);

        assertThat(hundred.plus(thirty)).isEqualTo(Money.ofMinor(13000, BRL));
        assertThat(hundred.minus(thirty)).isEqualTo(Money.ofMinor(7000, BRL));
        assertThat(thirty.minus(hundred)).isEqualTo(Money.ofMinor(-7000, BRL));
        assertThat(thirty.negate()).isEqualTo(Money.ofMinor(-3000, BRL));
    }

    @Test
    void rejectsCombiningDifferentCurrencies() {
        Money reais = Money.ofMinor(100, BRL);
        Money dollars = Money.ofMinor(100, Currency.getInstance("USD"));

        assertThatThrownBy(() -> reais.plus(dollars))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Cannot combine BRL with USD");
        assertThatThrownBy(() -> reais.minus(dollars)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failsInsteadOfOverflowing() {
        Money max = Money.ofMinor(Long.MAX_VALUE, BRL);
        Money min = Money.ofMinor(Long.MIN_VALUE, BRL);

        assertThatThrownBy(() -> max.plus(Money.ofMinor(1, BRL))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> min.minus(Money.ofMinor(1, BRL))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(min::negate).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void multipliesByARateWithoutRoundingWhenExact() {
        Money hundred = Money.ofMinor(10000, BRL);

        assertThat(hundred.times(new BigDecimal("0.0299"))).isEqualTo(Money.ofMinor(299, BRL));
        assertThat(hundred.times(new BigDecimal("0.7"))).isEqualTo(Money.ofMinor(7000, BRL));
    }

    /** 5% of each amount gives an exact half centavo in most rows: halves go to the even neighbor. */
    @ParameterizedTest
    @CsvSource({
        "10, 0", // 0.5 → 0
        "30, 2", // 1.5 → 2
        "50, 2", // 2.5 → 2
        "70, 4", // 3.5 → 4
        "-30, -2", // -1.5 → -2: the rule is symmetric around zero
        "31, 2", // 1.55 → 2: not a half, rounds to the nearest
        "29, 1", // 1.45 → 1
    })
    void roundsHalvesToTheEvenNeighbor(long minorUnits, long expected) {
        Money result = Money.ofMinor(minorUnits, BRL).times(new BigDecimal("0.05"));

        assertThat(result).isEqualTo(Money.ofMinor(expected, BRL));
    }

    @Test
    void failsInsteadOfOverflowingWhenMultiplying() {
        assertThatThrownBy(() -> Money.ofMinor(Long.MAX_VALUE, BRL).times(new BigDecimal("2")))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void allocatesExactlyWhenTheRatiosDivideTheAmount() {
        assertThat(Money.ofMinor(10000, BRL).allocate(70, 27, 3))
                .containsExactly(Money.ofMinor(7000, BRL), Money.ofMinor(2700, BRL), Money.ofMinor(300, BRL));
    }

    @Test
    void givesLeftOverMinorUnitsToTheLargestRemainders() {
        // R$ 1.00 in thirds: 33.33… each, one centavo left over, the tie goes to the first part.
        assertThat(Money.ofMinor(100, BRL).allocate(1, 1, 1))
                .containsExactly(Money.ofMinor(34, BRL), Money.ofMinor(33, BRL), Money.ofMinor(33, BRL));
        // R$ 0.05 as 30/70: exact shares 1.5 and 3.5, a tie on the fraction, so the first part wins.
        assertThat(Money.ofMinor(5, BRL).allocate(30, 70))
                .containsExactly(Money.ofMinor(2, BRL), Money.ofMinor(3, BRL));
        // R$ 0.05 as 2/1: exact shares 3.33… and 1.66…, so the second part has the larger fraction.
        assertThat(Money.ofMinor(5, BRL).allocate(2, 1)).containsExactly(Money.ofMinor(3, BRL), Money.ofMinor(2, BRL));
    }

    @Test
    void allocatesNegativeAmountsAsTheMirrorOfPositiveOnes() {
        assertThat(Money.ofMinor(-100, BRL).allocate(1, 1, 1))
                .containsExactly(Money.ofMinor(-34, BRL), Money.ofMinor(-33, BRL), Money.ofMinor(-33, BRL));
    }

    @Test
    void givesNothingToAZeroRatio() {
        assertThat(Money.ofMinor(100, BRL).allocate(1, 0, 1))
                .containsExactly(Money.ofMinor(50, BRL), Money.zero(BRL), Money.ofMinor(50, BRL));
    }

    @Test
    void rejectsInvalidRatios() {
        Money money = Money.ofMinor(100, BRL);

        assertThatThrownBy(money::allocate).hasMessage("At least one ratio is required");
        assertThatThrownBy(() -> money.allocate(1, -1)).hasMessage("Ratios must not be negative: -1");
        assertThatThrownBy(() -> money.allocate(0, 0)).hasMessage("At least one ratio must be positive");
    }
}
