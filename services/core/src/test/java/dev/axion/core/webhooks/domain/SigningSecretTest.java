package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SigningSecretTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    void isWhsecAndThirtyTwoBytesInBase64() {
        SigningSecret secret = SigningSecret.generate(random);

        assertThat(secret.text()).matches("whsec_[0-9A-Za-z+/]{43}=");
        assertThat(Base64.getDecoder().decode(secret.text().substring("whsec_".length())))
                .isEqualTo(secret.key());
    }

    @Test
    void generatesADifferentSecretEachTime() {
        assertThat(SigningSecret.generate(random)).isNotEqualTo(SigningSecret.generate(random));
    }

    @Test
    void isTheSecretOfItsBytes() {
        SigningSecret secret = SigningSecret.generate(random);

        assertThat(SigningSecret.of(secret.key())).isEqualTo(secret);
    }

    @Test
    void refusesBytesOfAnotherLength() {
        assertThatThrownBy(() -> SigningSecret.of(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void cannotBeChangedThroughItsBytes() {
        byte[] bytes = new byte[32];
        SigningSecret secret = SigningSecret.of(bytes);
        bytes[0] = 1;
        secret.key()[1] = 1;

        assertThat(secret.key()).containsOnly(0);
    }

    @Test
    void leavesTheSecretOutOfItsString() {
        SigningSecret secret = SigningSecret.generate(random);

        assertThat(secret.toString()).doesNotContain(secret.text().substring("whsec_".length()));
    }
}
