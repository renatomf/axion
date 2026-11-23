package dev.axion.core.webhooks.http;

import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.AttemptFailure;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Sends one request of a delivery (events and webhooks design, sections 7.2 and 8.3). Every
 * connection is made to an address {@link CheckedDnsResolver} accepted; redirects are never
 * followed, and there is no proxy, no cookie and no authentication cache, so each attempt is a new,
 * plain request. The response's body is never read.
 */
public final class WebhookClient implements AutoCloseable {

    /** The timeouts of section 7.2: to connect, and for the whole response once connected. */
    public record Timeouts(Duration connect, Duration response) {

        public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    static final String USER_AGENT = "Axion-Webhooks/1.0";

    private static final ContentType CLOUD_EVENT =
            ContentType.create("application/cloudevents+json", StandardCharsets.UTF_8);

    // At most 4 deliveries in flight per endpoint (section 8.2), so as many connections per receiver.
    private static final int CONNECTIONS_PER_RECEIVER = 4;

    private final CloseableHttpClient client;
    private final Timeouts timeouts;
    private final Clock clock;
    // Cancels a request whose whole response has not arrived in time, however slowly it trickles in.
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("webhook-deadlines").factory());

    /**
     * @param tls the context that verifies receivers' certificates: the JVM's trust store outside
     *     the tests
     * @param connections the most connections open at once, to every receiver together
     */
    public WebhookClient(CheckedDnsResolver resolver, SSLContext tls, Timeouts timeouts, int connections, Clock clock) {
        this.timeouts = timeouts;
        this.clock = clock;
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setTlsSocketStrategy(new DefaultClientTlsStrategy(tls))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(timeouts.connect()))
                                .setSocketTimeout(Timeout.of(timeouts.response()))
                                .build())
                        .setMaxConnTotal(connections)
                        .setMaxConnPerRoute(CONNECTIONS_PER_RECEIVER)
                        .build())
                .disableRedirectHandling()
                .disableCookieManagement()
                .disableAuthCaching()
                .disableAutomaticRetries()
                .disableContentCompression()
                .setUserAgent(USER_AGENT)
                .build();
    }

    /**
     * Posts the event's body, as the bytes given, to the URL.
     *
     * @param eventId the event's public ID, which receivers deduplicate by
     */
    public Attempt send(URI url, String eventId, byte[] body) {
        var request = new HttpPost(url);
        request.setHeader("webhook-id", eventId);
        request.setHeader("webhook-timestamp", String.valueOf(clock.instant().getEpochSecond()));
        request.setEntity(new ByteArrayEntity(body, CLOUD_EVENT));
        var expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = deadlines.schedule(
                () -> {
                    expired.set(true);
                    request.cancel();
                },
                timeouts.response().toMillis(),
                TimeUnit.MILLISECONDS);
        // Closed unread, so the connection is discarded instead of draining a body of any size.
        try (ClassicHttpResponse response = client.executeOpen(null, request, null)) {
            int status = response.getCode();
            return status >= 300 && status < 400
                    ? new Attempt.Failed(AttemptFailure.REDIRECT)
                    : new Attempt.Answered(status);
        } catch (IOException failure) {
            // A canceled request fails however its socket was closed.
            return new Attempt.Failed(expired.get() ? AttemptFailure.TIMEOUT : reason(failure));
        } finally {
            deadline.cancel(false);
        }
    }

    // Most specific first: a refused address is an UnknownHostException, a timeout an InterruptedIOException.
    private static AttemptFailure reason(IOException failure) {
        return switch (failure) {
            case RefusedAddressException refused -> AttemptFailure.ADDRESS_REFUSED;
            case UnknownHostException unknown -> AttemptFailure.UNRESOLVED;
            case InterruptedIOException timedOut -> AttemptFailure.TIMEOUT;
            case ConnectException refused -> AttemptFailure.CONNECTION_REFUSED;
            case SSLException tls -> AttemptFailure.TLS_ERROR;
            default -> AttemptFailure.CONNECTION_FAILED;
        };
    }

    @Override
    public void close() throws IOException {
        deadlines.shutdownNow();
        client.close();
    }
}
