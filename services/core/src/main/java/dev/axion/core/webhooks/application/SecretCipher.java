package dev.axion.core.webhooks.application;

import com.google.crypto.tink.AccessesPartialKey;
import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.AesGcmKey;
import com.google.crypto.tink.aead.AesGcmParameters;
import com.google.crypto.tink.util.SecretBytes;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.domain.SigningSecret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts signing secrets at rest with Tink's AES-256-GCM (events and webhooks design, section
 * 6.2). The endpoint's ID is the associated data: a ciphertext copied to another endpoint's row
 * fails to decrypt instead of signing that endpoint's requests.
 */
@Component
@EnableConfigurationProperties(WebhooksProperties.class)
class SecretCipher {

    private final Aead aead;

    SecretCipher(WebhooksProperties properties) throws GeneralSecurityException {
        AeadConfig.register();
        KeysetHandle keyset = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.importKey(key(properties.secretsKeyBytes()))
                        .withRandomId()
                        .makePrimary())
                .build();
        this.aead = keyset.getPrimitive(RegistryConfiguration.get(), Aead.class);
    }

    // Tink builds keys from raw bytes only where it is told so: the key comes from the environment,
    // not from a keyset Tink generated.
    @AccessesPartialKey
    private static AesGcmKey key(byte[] bytes) throws GeneralSecurityException {
        // NO_PREFIX: Tink's key ID would be random on each start, and ciphertexts must outlive it.
        AesGcmParameters parameters = AesGcmParameters.builder()
                .setKeySizeBytes(SigningSecret.LENGTH)
                .setIvSizeBytes(12)
                .setTagSizeBytes(16)
                .setVariant(AesGcmParameters.Variant.NO_PREFIX)
                .build();
        return AesGcmKey.builder()
                .setParameters(parameters)
                .setKeyBytes(SecretBytes.copyFrom(bytes, InsecureSecretKeyAccess.get()))
                .build();
    }

    byte[] encrypt(SigningSecret secret, WebhookEndpointId endpoint) {
        try {
            return aead.encrypt(secret.key(), associatedData(endpoint));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt a signing secret", e);
        }
    }

    /**
     * @throws GeneralSecurityException if the ciphertext was not encrypted for this endpoint with
     *     this key, or was changed since
     */
    SigningSecret decrypt(byte[] ciphertext, WebhookEndpointId endpoint) throws GeneralSecurityException {
        return SigningSecret.of(aead.decrypt(ciphertext, associatedData(endpoint)));
    }

    private static byte[] associatedData(WebhookEndpointId endpoint) {
        return endpoint.value().toString().getBytes(StandardCharsets.UTF_8);
    }
}
