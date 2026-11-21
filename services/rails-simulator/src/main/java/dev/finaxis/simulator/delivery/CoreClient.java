package dev.finaxis.simulator.delivery;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/** Sends a message to the core's endpoint and reports what came back, without ever throwing. */
@Component
@EnableConfigurationProperties(CoreProperties.class)
class CoreClient implements AutoCloseable {

    static final String CREDITS_PATH = "/rails/pix/credits";
    static final String CONTENT_TYPE = "application/json";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);

    private final URI credits;
    // HTTP/1.1: over cleartext, the default would ask every request to upgrade to HTTP/2 first.
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    CoreClient(CoreProperties core) {
        this.credits = core.url().resolve(CREDITS_PATH);
    }

    /**
     * What one attempt came to: the core's status and its {@code Retry-After}, or the reason no answer came.
     *
     * @param status the response's status code, if one came
     * @param retryAfter the response's {@code Retry-After}, if any
     * @param error {@code timeout} or {@code connection_failed}, if no answer came
     */
    record Response(
            @Nullable Integer status,
            @Nullable String retryAfter,
            @Nullable String error) {

        static Response failed(String error) {
            return new Response(null, null, error);
        }
    }

    Response send(byte[] message, String messageId) {
        var request = HttpRequest.newBuilder(credits)
                .timeout(RESPONSE_TIMEOUT)
                .header("Content-Type", CONTENT_TYPE)
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
