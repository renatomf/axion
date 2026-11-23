package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.CreatedEndpoint;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/** A registered endpoint's secret as the database holds it (events and webhooks design, section 6.2). */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
class StoredSecretTest {

    private final WebhooksApi webhooks;
    private final SecretCipher cipher;
    private final JdbcClient owner;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    StoredSecretTest(WebhooksApi webhooks, SecretCipher cipher, DatabaseOwner owner) {
        this.webhooks = webhooks;
        this.cipher = cipher;
        this.owner = owner.jdbc();
    }

    @Test
    void storesTheSecretEncrypted() {
        CreatedEndpoint created = create();

        byte[] ciphertext = ciphertext(created.endpoint().id());

        assertThat(ciphertext)
                .isNotEqualTo(Base64.getDecoder().decode(created.secret().substring("whsec_".length())));
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(created.secret());
    }

    @Test
    void decryptsToTheSecretReturnedOnce() throws GeneralSecurityException {
        CreatedEndpoint created = create();

        assertThat(cipher.decrypt(
                                ciphertext(created.endpoint().id()),
                                created.endpoint().id())
                        .text())
                .isEqualTo(created.secret());
    }

    @Test
    void doesNotDecryptForAnotherEndpoint() {
        CreatedEndpoint created = create();
        CreatedEndpoint other = create();

        byte[] copied = ciphertext(created.endpoint().id());

        assertThatThrownBy(() -> cipher.decrypt(copied, other.endpoint().id()))
                .isInstanceOf(GeneralSecurityException.class);
    }

    private CreatedEndpoint create() {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("charge.paid"));
        var result = TestCaller.call(tenant, Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint();
    }

    private byte[] ciphertext(WebhookEndpointId endpoint) {
        return owner.sql("SELECT ciphertext FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint.value())
                .query(byte[].class)
                .single();
    }
}
