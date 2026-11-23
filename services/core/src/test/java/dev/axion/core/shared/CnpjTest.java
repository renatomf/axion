package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CnpjTest {

    /** The alphanumeric example is the one the Receita Federal publishes for the new format. */
    @ParameterizedTest
    @CsvSource({
        "11222333000181, 11.222.333/0001-81",
        "12ABC34501DE35, 12.ABC.345/01DE-35",
        "A1B2C3D4E5F668, A1.B2C.3D4/E5F6-68",
    })
    void parsesNumericAndAlphanumericCnpjs(String value, String formatted) {
        assertThat(Cnpj.parse(value)).isEqualTo(new Cnpj(value));
        assertThat(Cnpj.parse(formatted).value()).isEqualTo(value);
        assertThat(new Cnpj(value).formatted()).isEqualTo(formatted);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "11222333000182", // wrong second check digit
                "11222333000191", // wrong first check digit
                "12ABC34501DE36",
                "12abc34501de35", // lowercase letters
                "12ABC34501DEA5", // a letter in the check digits
                "00000000000000", // correct check digits, but never issued
                "1122233300018", // 13 characters
                "112223330001811", // 15 characters
                "11.222.3330001-81", // partial punctuation
                "11 222 333 0001 81",
                "12ÁBC34501DE35",
                ""
            })
    void rejectsAnythingElse(String text) {
        assertThatThrownBy(() -> Cnpj.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRevealTheNumber() {
        assertThat(new Cnpj("12ABC34501DE35")).hasToString("**.ABC.345/01DE-**");
        assertThatThrownBy(() -> Cnpj.parse("12ABC34501DE36")).hasMessageNotContaining("12ABC");
    }
}
