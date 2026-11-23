package dev.axion.simulator.payer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CpfTest {

    @ParameterizedTest
    @ValueSource(strings = {"52601815906", "11144477735", "24681357928", "13579246828", "98765432100"})
    void acceptsAValidCpf(String cpf) {
        assertThat(Cpf.isValid(cpf)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "52601815907", // second check digit wrong
                "52601815916", // first check digit wrong
                "11111111111", // valid check digits, but one repeated digit
                "5260181590", // ten digits
                "526018159060", // twelve digits
                "526.018.159-06", // formatted
                "5260181590a",
                ""
            })
    void refusesAnythingElse(String cpf) {
        assertThat(Cpf.isValid(cpf)).isFalse();
    }
}
