package dev.axion.core.access;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.access.domain.ApiKeySecret;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.Environment;
import dev.axion.core.tenancy.Permission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IssuedApiKeyTest {

    private final ApiKeySecret secret = ApiKeySecret.generate(Environment.TEST, new SecureRandom());
    private final IssuedApiKey key = new IssuedApiKey(
            new ApiKeyId(UUID.randomUUID()), "Checkout", Set.of(Permission.CHARGES_READ), Instant.EPOCH, secret);

    @Test
    void revealsTheSecretOnlyThroughItsAccessor() {
        assertThat(key.secret()).isEqualTo(secret.reveal());
        assertThat(key.lastFour()).isEqualTo(secret.lastFour());
    }

    @Test
    void masksTheSecretWhenPrinted() {
        assertThat(key.toString()).doesNotContain(secret.reveal()).contains("sk_test_…" + secret.lastFour());
    }

    @Test
    void showsItsIdAsAPublicId() {
        assertThat(key.toString()).contains("id=key_");
    }
}
