package dev.finaxis.core.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransactionIdTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void isGeneratedAs25LettersOrDigits() {
        String value = TransactionId.generate(random).value();

        assertThat(value).hasSize(TransactionId.LENGTH).matches("[A-Za-z0-9]+");
    }

    @Test
    void drawsEveryCharacterOfTheAlphabet() {
        Set<Character> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            TransactionId.generate(random).value().chars().forEach(c -> seen.add((char) c));
        }

        assertThat(seen).hasSize(TransactionId.ALPHABET.length());
    }

    @Test
    void neverRepeatsAcrossDraws() {
        Set<TransactionId> drawn = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            drawn.add(TransactionId.generate(random));
        }

        assertThat(drawn).hasSize(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "fx7Kq2mP9wR4tY6uA8sD3fG5h"})
    void acceptsOneTo25LettersOrDigits(String value) {
        assertThat(new TransactionId(value).value()).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "***", "abc-123", "fx7Kq2mP9wR4tY6uA8sD3fG5hX", "çãé"})
    void refusesAnythingElse(String value) {
        assertThatThrownBy(() -> new TransactionId(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 to 25 letters or digits");
    }
}
