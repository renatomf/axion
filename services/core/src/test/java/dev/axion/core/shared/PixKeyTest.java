package dev.axion.core.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PixKeyTest {

    @ParameterizedTest
    @CsvSource({
        "52998224725, CPF",
        "12ABC34501DE35, CNPJ",
        "11222333000181, CNPJ",
        "maria.silva+escola@example.com.br, EMAIL",
        "+5511987654321, PHONE",
        "123e4567-e89b-42d3-a456-426614174000, RANDOM",
    })
    void tellsTheTypeFromTheCanonicalForm(String text, PixKey.Type type) {
        PixKey key = PixKey.parse(text);

        assertThat(key.type()).isEqualTo(type);
        assertThat(key.value()).isEqualTo(text);
        assertThat(key).isEqualTo(new PixKey(type, text));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "529.982.247-25", // CPF with punctuation
                "52998224724", // CPF with a wrong check digit
                "12.ABC.345/01DE-35", // CNPJ with punctuation
                "Maria@example.com", // uppercase email
                "maria@", // email without a domain
                "maria@-example.com",
                "11987654321", // phone without +55 is not a CPF either
                "+551187654321", // landline
                "+5501987654321", // area code with a zero
                "+15551234567", // not Brazilian
                "123E4567-E89B-42D3-A456-426614174000", // uppercase UUID
                "123e4567e89b42d3a456426614174000", // UUID without hyphens
                ""
            })
    void rejectsKeysThatAreNotCanonical(String text) {
        assertThatThrownBy(() -> PixKey.parse(text)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAValueOfAnotherType() {
        assertThatThrownBy(() -> new PixKey(PixKey.Type.PHONE, "52998224725"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnEmailLongerThan77Characters() {
        String local = "a".repeat(65);

        assertThat(PixKey.parse(local + "@example.com").value()).hasSize(77);
        assertThatThrownBy(() -> PixKey.parse(local + "a@example.com")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void doesNotRevealTheValue() {
        assertThat(PixKey.parse("maria@example.com")).hasToString("PixKey[EMAIL]");
        assertThatThrownBy(() -> PixKey.parse("Maria@example.com")).hasMessageNotContaining("aria");
    }
}
