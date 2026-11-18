package dev.finaxis.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"52998224725", "529.982.247-25"})
    void parsesDigitsWithOrWithoutPunctuation(String text) {
        Cpf cpf = Cpf.parse(text);

        assertThat(cpf.digits()).isEqualTo("52998224725");
        assertThat(cpf.formatted()).isEqualTo("529.982.247-25");
        assertThat(cpf).isEqualTo(new Cpf("52998224725"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678909", "00000000191"})
    void acceptsNumbersWithCorrectCheckDigits(String digits) {
        assertThat(new Cpf(digits).digits()).isEqualTo(digits);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "52998224724", // wrong second check digit
                "52998224715", // wrong first check digit
                "11111111111", // correct check digits, but never issued
                "00000000000",
                "5299822472", // 10 digits
                "529982247250", // 12 digits
                "529.982.24725", // partial punctuation
                "529 982 247 25",
                " 52998224725",
                "5299822472a",
                ""
            })
    void rejectsAnythingElse(String text) {
        assertThatThrownBy(() -> Cpf.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRevealTheNumber() {
        Cpf cpf = new Cpf("52998224725");

        assertThat(cpf).hasToString("***.982.247-**");
        assertThatThrownBy(() -> Cpf.parse("52998224724")).hasMessageNotContaining("5299822472");
    }
}
