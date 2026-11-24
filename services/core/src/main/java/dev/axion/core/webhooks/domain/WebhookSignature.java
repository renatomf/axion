package dev.axion.core.webhooks.domain;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@code webhook-signature} header of a request (events and webhooks design, section 8.4), as
 * Standard Webhooks defines it: HMAC-SHA256 over {@code {webhook-id}.{webhook-timestamp}.{body}},
 * in base64 after the scheme version {@code v1,}. The body is signed as the bytes sent, so a
 * receiver verifies it before parsing it.
 */
public final class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";

    private static final String VERSION = "v1,";

    private WebhookSignature() {}

    /**
     * One signature for each secret, in the order given, separated by a space: during a grace period,
     * the new secret's first and the old one's after it.
     *
     * @param timestamp the attempt's time, in Unix seconds, as sent in {@code webhook-timestamp}
     */
    public static String header(List<SigningSecret> secrets, String webhookId, long timestamp, byte[] body) {
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("A request is signed with one secret at least");
        }
        return secrets.stream()
                .map(secret -> sign(secret.key(), webhookId, timestamp, body))
                .collect(Collectors.joining(" "));
    }

    // Keyed with raw bytes, so the specification's vectors, whose secret is 24 bytes, check it too.
    static String sign(byte[] key, String webhookId, long timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(key, ALGORITHM));
            mac.update((webhookId + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return VERSION + Base64.getEncoder().encodeToString(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("Every JVM signs with " + ALGORITHM, e);
        }
    }
}
