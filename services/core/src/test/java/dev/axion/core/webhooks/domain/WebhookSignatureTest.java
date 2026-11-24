package dev.axion.core.webhooks.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The signature against the test vectors of Standard Webhooks' reference library, {@code
 * libraries/python/tests/test_webhooks.py} in github.com/standard-webhooks/standard-webhooks.
 */
class WebhookSignatureTest {

    private static final byte[] VECTOR_KEY = Base64.getDecoder().decode("MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw");

    private static final String VECTOR_ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";

    private static final long VECTOR_TIMESTAMP = 1_614_265_330L;

    private final SigningSecret current = SigningSecret.of(bytes(1));
    private final SigningSecret previous = SigningSecret.of(bytes(2));

    @Test
    void matchesTheSpecificationsVector() {
        byte[] body = "{\"test\": 2432232314}".getBytes(StandardCharsets.UTF_8);

        assertThat(WebhookSignature.sign(VECTOR_KEY, VECTOR_ID, VECTOR_TIMESTAMP, body))
                .isEqualTo("v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=");
    }

    @Test
    void signsTheBodyAsBytesNotAsText() {
        byte[] body = new byte[256];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }

        assertThat(WebhookSignature.sign(VECTOR_KEY, VECTOR_ID, VECTOR_TIMESTAMP, body))
                .isEqualTo("v1,sEcZ042G8Crqgxbybb3y0eDxQR3TcfYsk11I1XULhSI=");
    }

    @Test
    void carriesOneSignaturePerSecretInTheOrderGiven() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);

        String header = WebhookSignature.header(List.of(current, previous), "evt_1", 1_795_442_591L, body);

        assertThat(header.split(" "))
                .containsExactly(
                        WebhookSignature.sign(current.key(), "evt_1", 1_795_442_591L, body),
                        WebhookSignature.sign(previous.key(), "evt_1", 1_795_442_591L, body));
    }

    @Test
    void changesWithTheIdTheTimestampAndTheBody() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = WebhookSignature.header(List.of(current), "evt_1", 100, body);

        assertThat(WebhookSignature.header(List.of(current), "evt_2", 100, body))
                .isNotEqualTo(signature);
        assertThat(WebhookSignature.header(List.of(current), "evt_1", 101, body))
                .isNotEqualTo(signature);
        assertThat(WebhookSignature.header(List.of(current), "evt_1", 100, "{ }".getBytes(StandardCharsets.UTF_8)))
                .isNotEqualTo(signature);
    }

    @Test
    void needsASecret() {
        assertThatThrownBy(() -> WebhookSignature.header(List.of(), "evt_1", 100, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(int value) {
        byte[] key = new byte[SigningSecret.LENGTH];
        Arrays.fill(key, (byte) value);
        return key;
    }
}
