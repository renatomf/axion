package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
}
