package dev.axion.core.webhooks.http;

import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.AttemptFailure;
import dev.axion.core.webhooks.domain.SentAttempt;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
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
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

/**
 * Sends one request of a delivery (events and webhooks design, sections 7.2 and 8.3). Every
 * connection is made to an address {@link CheckedDnsResolver} accepted; redirects are never
 * followed, and there is no proxy, no cookie and no authentication cache, so each attempt is a new,
 * plain request. Of the response's body, only what the delivery log keeps is read.
 */
public final class WebhookClient implements AutoCloseable {

    /** The timeouts of section 7.2: to connect, and for the whole response once connected. */
    public record Timeouts(Duration connect, Duration response) {

        public static final Timeouts DEFAULT = new Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    static final String USER_AGENT = "Axion-Webhooks/1.0";

    private static final ContentType CLOUD_EVENT =
            ContentType.create("application/cloudevents+json", StandardCharsets.UTF_8);

    private final CloseableHttpClient client;
    private final Timeouts timeouts;
    // Cancels a request whose whole response has not arrived in time, however slowly it trickles in.
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("webhook-deadlines").factory());

    /**
     * @param tls the context that verifies receivers' certificates: the JVM's trust store outside
     *     the tests
     * @param connections the most connections open at once, to every receiver together
     */
    public WebhookClient(CheckedDnsResolver resolver, SSLContext tls, Timeouts timeouts, int connections) {
        this.timeouts = timeouts;
        this.client = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(resolver)
                        .setTlsSocketStrategy(new DefaultClientTlsStrategy(tls))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(timeouts.connect()))
                                .setSocketTimeout(Timeout.of(timeouts.response()))
                                .build())
                        // Endpoints of several tenants may share a host; the queue, not the pool, bounds
                        // what each endpoint holds (section 8.2).
                        .setMaxConnTotal(connections)
                        .setMaxConnPerRoute(connections)
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
     * Posts the event's body, as the bytes signed, to the URL, with the headers that verify it, and
     * reports what came of it, how long it took and the start of the answer.
     */
    public SentAttempt send(URI url, SignedEvent event) {
        var request = new HttpPost(url);
        request.setHeader("webhook-id", event.id());
        request.setHeader("webhook-timestamp", String.valueOf(event.timestamp()));
        request.setHeader("webhook-signature", event.signature());
        request.setEntity(new ByteArrayEntity(event.body(), CLOUD_EVENT));
        var expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = deadlines.schedule(
                () -> {
                    expired.set(true);
                    request.cancel();
                },
                timeouts.response().toMillis(),
                TimeUnit.MILLISECONDS);
        long started = System.nanoTime();
        try {
            return answer(request, started);
        } catch (IOException failure) {
            // A canceled request fails however its socket was closed.
            Attempt failed = new Attempt.Failed(expired.get() ? AttemptFailure.TIMEOUT : reason(failure));
            return new SentAttempt(failed, since(started), Optional.empty());
        } finally {
            deadline.cancel(false);
        }
    }

    private SentAttempt answer(HttpPost request, long started) throws IOException {
        ClassicHttpResponse response = client.executeOpen(null, request, null);
        try {
            int status = response.getCode();
            if (status >= 300 && status < 400) {
                return new SentAttempt(new Attempt.Failed(AttemptFailure.REDIRECT), since(started), Optional.empty());
            }
            Optional<String> excerpt = excerpt(response.getEntity());
            return new SentAttempt(new Attempt.Answered(status), since(started), excerpt);
        } finally {
            // Aborted before it is closed: closing reads the rest of the body, however long it is,
            // to keep the connection; aborting discards the connection instead.
            request.cancel();
            try {
                response.close();
            } catch (IOException discarded) {
                // The connection is gone already; the answer was read.
            }
        }
    }

    // The status decides the attempt: a body that fails or runs out of time while it is read keeps
    // what arrived, and the answer stands. The stream is left open for the abort to discard.
    private static Optional<String> excerpt(HttpEntity entity) {
        if (entity == null) {
            return Optional.empty();
        }
        byte[] buffer = new byte[SentAttempt.MAX_EXCERPT_BYTES];
        int read = 0;
        try {
            InputStream body = entity.getContent();
            int chunk;
            while (read < buffer.length && (chunk = body.read(buffer, read, buffer.length - read)) != -1) {
                read += chunk;
            }
        } catch (IOException cut) {
            // Keeps what was read before the body failed.
        }
        return read == 0 ? Optional.empty() : Optional.of(text(Arrays.copyOf(buffer, read)));
    }

    // Whatever the receiver sent: bytes that are not UTF-8, a character cut at the limit included,
    // become U+FFFD. NUL is dropped, since PostgreSQL text cannot hold it.
    static String text(byte[] bytes) {
        var decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString().replace("\0", "");
        } catch (CharacterCodingException impossible) {
            throw new IllegalStateException("A replacing decoder never fails", impossible);
        }
    }

    private static Duration since(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
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
