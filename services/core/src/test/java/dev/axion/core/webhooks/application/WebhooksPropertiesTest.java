package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class WebhooksPropertiesTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void readsAKeyOfThirtyTwoBytes() {
        assertThat(new WebhooksProperties(KEY).secretsKeyBytes()).hasSize(32);
    }

    @Test
    void refusesNoKey() {
        assertThatThrownBy(() -> new WebhooksProperties(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("axion.webhooks.secrets-key must be set");
    }

    @Test
    void refusesAKeyThatIsNotBase64() {
        assertThatThrownBy(() -> new WebhooksProperties("not base64!"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be base64");
    }

    @Test
    void refusesAKeyOfAnotherLength() {
        assertThatThrownBy(() -> new WebhooksProperties(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be 32 bytes, not 16");
    }

    @Test
    void leavesTheKeyOutOfItsString() {
        assertThat(new WebhooksProperties(KEY).toString()).doesNotContain(KEY);
    }
}
