package dev.axion.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IdempotencyKeyTest {

    @Test
    void acceptsAUuid() {
        assertThat(new IdempotencyKey("0f8c5b9e-6f4e-4d0a-9a51-3c1f0c2b7d11").value())
                .isEqualTo("0f8c5b9e-6f4e-4d0a-9a51-3c1f0c2b7d11");
    }

    @Test
    void acceptsEveryVisibleAsciiCharacter() {
        var visible = new StringBuilder();
        for (char c = 0x21; c <= 0x7E; c++) {
            visible.append(c);
        }

        assertThat(new IdempotencyKey(visible.toString()).value()).hasSize(94);
    }

    @Test
    void acceptsTheQuotedFormOfTheIetfDraft() {
        assertThat(new IdempotencyKey("\"8e03978e-40d5-43e8-bc93-6894a57f9324\"").value())
                .startsWith("\"");
    }

    @Test
    void acceptsOneCharacterAndTheMaximumLength() {
        assertThat(new IdempotencyKey("a").value()).hasSize(1);
        assertThat(new IdempotencyKey("a".repeat(IdempotencyKey.MAX_LENGTH)).value())
                .hasSize(255);
    }

    @Test
    void rejectsAnEmptyKey() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(""));
    }

    @Test
    void rejectsAKeyLongerThanTheMaximum() {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey("a".repeat(256)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"with space", "tab\there", "line\nfeed", "del\u007F", "café", "emoji😀"})
    void rejectsCharactersOutsideVisibleAscii(String key) {
        assertThatIllegalArgumentException().isThrownBy(() -> new IdempotencyKey(key));
    }

    @Test
    void comparesKeysByTheirExactCharacters() {
        assertThat(new IdempotencyKey("Order-1")).isEqualTo(new IdempotencyKey("Order-1"));
        assertThat(new IdempotencyKey("Order-1")).isNotEqualTo(new IdempotencyKey("order-1"));
    }

    @Test
    void leavesTheKeyOutOfTheErrorMessage() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IdempotencyKey("secret value"))
                .withMessageNotContaining("secret");
    }

    @Test
    void printsTheHashOfTheKeyInsteadOfTheKey() {
        var key = new IdempotencyKey("customer-4421-order");

        assertThat(key.toString()).doesNotContain("customer").contains(key.hash());
        assertThat(key.hash())
                .hasSize(64)
                .isEqualTo(new IdempotencyKey("customer-4421-order").hash())
                .isNotEqualTo(new IdempotencyKey("customer-4421-order2").hash());
    }
}
