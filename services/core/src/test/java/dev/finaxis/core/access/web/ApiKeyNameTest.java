package dev.finaxis.core.access.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link ApiKeyName}, checked by a plain validator, without Spring. */
class ApiKeyNameTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    /** An emoji outside the Basic Multilingual Plane: two Java chars, one code point. */
    private static final String EMOJI = "🔑";

    record Body(@ApiKeyName @Nullable String name) {}

    @AfterAll
    static void close() {
        FACTORY.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Checkout", "x", " padded ", "Caixa número 2"})
    void acceptsANameWithText(String name) {
        assertThat(valid(name)).isTrue();
    }

    @ParameterizedTest
    // An em space and an ideographic space: blank to String.isBlank, not to the standard NotBlank.
    @ValueSource(strings = {"", " ", "\t\n", " ", "　"})
    void rejectsABlankName(String name) {
        assertThat(valid(name)).isFalse();
    }

    @Test
    void countsCharactersByCodePoint() {
        assertThat(valid(EMOJI.repeat(100))).isTrue();
        assertThat(valid(EMOJI.repeat(101))).isFalse();
        assertThat(valid("a".repeat(100))).isTrue();
        assertThat(valid("a".repeat(101))).isFalse();
    }

    @Test
    void leavesNullToNotNull() {
        assertThat(valid(null)).isTrue();
    }

    private static boolean valid(@Nullable String name) {
        return VALIDATOR.validate(new Body(name)).isEmpty();
    }
}
