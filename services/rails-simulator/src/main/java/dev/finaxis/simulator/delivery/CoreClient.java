package dev.finaxis.simulator.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Signs a message, sends it to the core's endpoint and reports what came back, without ever throwing. */
@Component
@EnableConfigurationProperties(CoreProperties.class)
class CoreClient implements AutoCloseable {

    static final String CREDITS_PATH = "/rails/pix/credits";
    static final String CONTENT_TYPE = "application/json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    private final URI credits;
    private final MessageSigner signer;
    private final Clock clock;
    // HTTP/1.1: over cleartext, the default would ask every request to upgrade to HTTP/2 first.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CoreClient(CoreProperties core, MessageSigner signer, Clock clock) {
        this.credits = core.url().resolve(CREDITS_PATH);
        this.signer = signer;
        this.clock = clock;
    }

    /**
     * What one attempt came to: the core's status and its {@code Retry-After}, or the reason no answer came.
     *
     * @param status the response's status code, if one came
     * @param retryAfter the response's {@code Retry-After}, if any
     * @param error {@code timeout}, {@code connection_failed} or, when a chaos fault kept the attempt from
     *     being sent, {@code not_sent}, if no answer came
     */
    record Response(
            @Nullable Integer status,
            @Nullable String retryAfter,
            @Nullable String error) {

        static final String NOT_SENT = "not_sent";

        static Response failed(String error) {
            return new Response(null, null, error);
        }
    }

    /** Each attempt is signed afresh, so its {@code created} is the moment it is sent. */
    Response send(byte[] message, String messageId) {
        var signature = signer.sign("POST", credits.getRawPath(), CONTENT_TYPE, message, clock.instant());
        var request = HttpRequest.newBuilder(credits)
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", CONTENT_TYPE)
                .header("Content-Digest", signature.contentDigest())
                .header("Signature-Input", signature.signatureInput())
                .header("Signature", signature.signature())
                .header("X-Correlation-Id", messageId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(message))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            return new Response(
                    response.statusCode(),
                    response.headers().firstValue("Retry-After").orElse(null),
                    null);
        } catch (HttpTimeoutException timeout) {
            return Response.failed("timeout");
        } catch (IOException failure) {
            return Response.failed("connection_failed");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Response.failed("connection_failed");
        }
    }

    @Override
    public void close() {
        http.close();
    }
}
