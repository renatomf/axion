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
}
