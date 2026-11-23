package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.domain.SigningSecret;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SecretCipherTest {

    private final SecureRandom random = new SecureRandom();
    private final SecretCipher cipher = cipher(randomKey());
    private final SigningSecret secret = SigningSecret.generate(random);
    private final WebhookEndpointId endpoint = new WebhookEndpointId(UUID.randomUUID());

    @Test
    void decryptsWhatItEncrypted() throws GeneralSecurityException {
        byte[] ciphertext = cipher.encrypt(secret, endpoint);

        assertThat(cipher.decrypt(ciphertext, endpoint)).isEqualTo(secret);
    }

    @Test
    void keepsTheSecretOutOfTheCiphertext() {
        assertThat(Base64.getEncoder().encodeToString(cipher.encrypt(secret, endpoint)))
                .doesNotContain(secret.text().substring("whsec_".length()));
    }

    @Test
    void encryptsTheSameSecretDifferentlyEachTime() {
        assertThat(cipher.encrypt(secret, endpoint)).isNotEqualTo(cipher.encrypt(secret, endpoint));
    }

    @Test
    void refusesToDecryptForAnotherEndpoint() {
        byte[] ciphertext = cipher.encrypt(secret, endpoint);

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, new WebhookEndpointId(UUID.randomUUID())))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void refusesToDecryptWithAnotherKey() {
        byte[] ciphertext = cipher.encrypt(secret, endpoint);

        assertThatThrownBy(() -> cipher(randomKey()).decrypt(ciphertext, endpoint))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void refusesAChangedCiphertext() {
        byte[] ciphertext = cipher.encrypt(secret, endpoint);
        ciphertext[ciphertext.length / 2] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(ciphertext, endpoint)).isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void decryptsAfterARestartWithTheSameKey() throws GeneralSecurityException {
        String key = randomKey();
        byte[] ciphertext = cipher(key).encrypt(secret, endpoint);

        assertThat(cipher(key).decrypt(ciphertext, endpoint)).isEqualTo(secret);
    }

    private String randomKey() {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static SecretCipher cipher(String key) {
        try {
            return new SecretCipher(new WebhooksProperties(key));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
