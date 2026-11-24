package dev.axion.core.webhooks.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.axion.core.webhooks.TestReceiver;
import dev.axion.core.webhooks.domain.Attempt;
import dev.axion.core.webhooks.domain.AttemptFailure;
import dev.axion.core.webhooks.domain.HostResolver;
import dev.axion.core.webhooks.domain.LocalReceivers;
import dev.axion.core.webhooks.domain.PublicAddressPolicy;
import dev.axion.core.webhooks.domain.SentAttempt;
import dev.axion.core.webhooks.domain.UrlValidation;
import dev.axion.core.webhooks.domain.WebhookUrlValidator;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookClient} against a local HTTPS server (events and webhooks design, sections 7.2 and
 * 8.3). The receiver's name resolves to the loopback address, which the tests allow unless they
 * test the address policy itself.
 */
class WebhookClientTest {

    private static final Instant NOW = Instant.parse("2026-11-23T14:03:11Z");
    private static final String EVENT_ID = "evt_01jdr3b6f7g8h9j0k1m2n3p4q5";
    private static final byte[] BODY = "{\"specversion\":\"1.0\"}".getBytes(StandardCharsets.UTF_8);
    private static final String SIGNATURE = "v1,K5oZfzN95Z9UVu1EsfQmfVNQhnkZ2pj9o9NDN/H/pI4=";
    private static final SignedEvent EVENT = new SignedEvent(EVENT_ID, NOW.getEpochSecond(), SIGNATURE, BODY);
    private static final Predicate<InetAddress> LOOPBACK = InetAddress::isLoopbackAddress;

    private final TestReceiver receiver = TestReceiver.start();

    @AfterEach
    void stop() {
        receiver.close();
    }

    @Test
    void postsTheBodyAsGivenWithTheHeadersThatVerifyIt() throws IOException {
        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/hooks"), EVENT).attempt()).isEqualTo(new Attempt.Answered(204));
        }

        assertThat(receiver.requests("/hooks")).singleElement().satisfies(request -> {
            assertThat(request.body()).isEqualTo(new String(BODY, StandardCharsets.UTF_8));
            assertThat(request.headers())
                    .containsEntry("content-type", "application/cloudevents+json; charset=UTF-8")
                    .containsEntry("user-agent", "Axion-Webhooks/1.0")
                    .containsEntry("webhook-id", EVENT_ID)
                    .containsEntry("webhook-timestamp", String.valueOf(NOW.getEpochSecond()))
                    .containsEntry("webhook-signature", SIGNATURE)
                    .doesNotContainKeys("cookie", "authorization");
        });
    }

    @Test
    void succeedsOnlyOnA2xx() throws IOException {
        receiver.answer("/accepted", new TestReceiver.Answer.Status(202));
        receiver.answer("/broken", new TestReceiver.Answer.Status(500));

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/accepted"), EVENT).attempt().succeeded())
                    .isTrue();
            assertThat(client.send(receiver.url("/broken"), EVENT).attempt()).isEqualTo(new Attempt.Answered(500));
            assertThat(client.send(receiver.url("/broken"), EVENT).attempt().succeeded())
                    .isFalse();
        }
    }

    @Test
    void neverFollowsARedirect() throws IOException {
        receiver.answer(
                "/moved",
                new TestReceiver.Answer.Redirect(receiver.url("/elsewhere").toString()));

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/moved"), EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.REDIRECT));
        }
        assertThat(receiver.requests("/elsewhere")).isEmpty();
    }

    @Test
    void connectsToNoAddressThePolicyRefuses() throws IOException {
        try (var client = client(TestReceiver.loopbackDns(), PublicAddressPolicy::isPublic)) {
            assertThat(client.send(receiver.url("/hooks"), EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.ADDRESS_REFUSED));
        }
        assertThat(receiver.requests("/hooks")).isEmpty();
    }

    @Test
    void connectsToNoAddressWrittenInTheUrl() throws IOException {
        URI literal = URI.create("https://127.0.0.1:" + receiver.url("/").getPort() + "/hooks");

        try (var client = client(HostResolver.SYSTEM, PublicAddressPolicy::isPublic)) {
            assertThat(client.send(literal, EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.ADDRESS_REFUSED));
        }
        assertThat(receiver.requests("/hooks")).isEmpty();
    }

    @Test
    void refusesANameThatResolvedToAPublicAddressAtRegistrationAndToAPrivateOneNow() throws Exception {
        var lookups = new AtomicInteger();
        HostResolver rebinding = host -> List.of(InetAddress.getByAddress(
                host,
                lookups.getAndIncrement() == 0
                        ? new byte[] {93, (byte) 184, (byte) 215, 14}
                        : new byte[] {(byte) 169, (byte) 254, (byte) 169, (byte) 254}));
        URI url = URI.create("https://" + TestReceiver.HOST + "/hooks");

        assertThat(new WebhookUrlValidator(rebinding).validate(url.toString()))
                .isInstanceOf(UrlValidation.Accepted.class);
        try (var client = client(rebinding, PublicAddressPolicy::isPublic)) {
            assertThat(client.send(url, EVENT).attempt()).isEqualTo(new Attempt.Failed(AttemptFailure.ADDRESS_REFUSED));
        }
    }

    @Test
    void connectsOnlyToTheAddressesThePolicyAllows() throws IOException {
        HostResolver mixed = host -> List.of(
                InetAddress.getByAddress(host, new byte[] {10, 0, 0, 7}),
                InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
        Predicate<InetAddress> loopbackOnly = LOOPBACK;

        try (var client = client(mixed, loopbackOnly)) {
            assertThat(client.send(receiver.url("/hooks"), EVENT).attempt().succeeded())
                    .isTrue();
        }
    }

    @Test
    void postsOverHttpToALocalReceiverOnALocalPort() throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        server.createContext("/hooks", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.start();
        int port = server.getAddress().getPort();
        URI url = URI.create("http://localhost:" + port + "/hooks");
        HostResolver loopback = host -> List.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
        try (var local = new WebhookClient(
                        new CheckedDnsResolver(
                                loopback, PublicAddressPolicy::isPublic, new LocalReceivers(Set.of(port))),
                        receiver.trust(),
                        WebhookClient.Timeouts.DEFAULT,
                        4);
                var deployed = client(loopback, PublicAddressPolicy::isPublic)) {
            assertThat(local.send(url, EVENT).attempt()).isEqualTo(new Attempt.Answered(204));
            assertThat(deployed.send(url, EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.ADDRESS_REFUSED));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void timesOutAResponseThatTakesTooLong() throws IOException {
        receiver.answer("/slow", new TestReceiver.Answer.Late(Duration.ofSeconds(3), 204));
        var timeouts = new WebhookClient.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(300));
        long started = System.nanoTime();

        try (var client = new WebhookClient(
                new CheckedDnsResolver(TestReceiver.loopbackDns(), LOOPBACK), receiver.trust(), timeouts, 4)) {
            assertThat(client.send(receiver.url("/slow"), EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.TIMEOUT));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void failsTheHandshakeOfACertificateForAnotherName() throws IOException {
        URI other = URI.create(
                "https://other." + TestReceiver.HOST + ":" + receiver.url("/").getPort() + "/hooks");

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(other, EVENT).attempt()).isEqualTo(new Attempt.Failed(AttemptFailure.TLS_ERROR));
        }
        assertThat(receiver.requests("/hooks")).isEmpty();
    }

    @Test
    void failsTheHandshakeOfACertificateNoOneTrusts() throws Exception {
        try (var client = new WebhookClient(
                new CheckedDnsResolver(TestReceiver.loopbackDns(), LOOPBACK),
                SSLContext.getDefault(),
                WebhookClient.Timeouts.DEFAULT,
                4)) {
            assertThat(client.send(receiver.url("/hooks"), EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.TLS_ERROR));
        }
    }

    @Test
    void reportsARefusedConnection() throws IOException {
        int closedPort;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }
        URI url = URI.create("https://" + TestReceiver.HOST + ":" + closedPort + "/hooks");

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(url, EVENT).attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.CONNECTION_REFUSED));
        }
    }

    @Test
    void reportsANameThatDoesNotResolve() throws IOException {
        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(URI.create("https://unknown.example.org/hooks"), EVENT)
                            .attempt())
                    .isEqualTo(new Attempt.Failed(AttemptFailure.UNRESOLVED));
        }
    }

    @Test
    void timesTheAttempt() throws IOException {
        receiver.answer("/slow", new TestReceiver.Answer.Late(Duration.ofMillis(200), 204));

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/slow"), EVENT).duration())
                    .isBetween(Duration.ofMillis(200), Duration.ofSeconds(5));
        }
    }

    @Test
    void keepsTheStartOfTheResponseBody() throws IOException {
        receiver.answer("/large", new TestReceiver.Answer.Body(500, "a".repeat(5_000)));
        receiver.answer("/small", new TestReceiver.Answer.Body(200, "ok"));

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/large"), EVENT))
                    .satisfies(sent -> assertThat(sent.attempt()).isEqualTo(new Attempt.Answered(500)))
                    .satisfies(sent -> assertThat(sent.responseExcerpt()).hasValue("a".repeat(1_024)));
            assertThat(client.send(receiver.url("/small"), EVENT).responseExcerpt())
                    .hasValue("ok");
            assertThat(client.send(receiver.url("/hooks"), EVENT).responseExcerpt())
                    .isEmpty();
        }
    }

    @Test
    void keepsNoResponseOfAFailedAttempt() throws IOException {
        receiver.answer(
                "/moved",
                new TestReceiver.Answer.Redirect(receiver.url("/elsewhere").toString()));

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            assertThat(client.send(receiver.url("/moved"), EVENT).responseExcerpt())
                    .isEmpty();
        }
    }

    @Test
    void readsNoMoreOfABodyThatNeverEnds() throws IOException {
        receiver.answer("/endless", new TestReceiver.Answer.Endless(200, 256, Duration.ofMillis(5)));
        long started = System.nanoTime();

        try (var client = client(TestReceiver.loopbackDns(), LOOPBACK)) {
            SentAttempt sent = client.send(receiver.url("/endless"), EVENT);

            assertThat(sent.attempt()).isEqualTo(new Attempt.Answered(200));
            assertThat(sent.responseExcerpt())
                    .hasValueSatisfying(excerpt -> assertThat(excerpt).hasSize(1_024));
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void keepsTheAnswerOfABodyThatOutlastsTheTimeout() throws IOException {
        receiver.answer("/trickle", new TestReceiver.Answer.Endless(200, 1, Duration.ofMillis(100)));
        var timeouts = new WebhookClient.Timeouts(Duration.ofSeconds(1), Duration.ofMillis(500));

        try (var client = new WebhookClient(
                new CheckedDnsResolver(TestReceiver.loopbackDns(), LOOPBACK), receiver.trust(), timeouts, 4)) {
            SentAttempt sent = client.send(receiver.url("/trickle"), EVENT);

            assertThat(sent.attempt()).isEqualTo(new Attempt.Answered(200));
            assertThat(sent.responseExcerpt())
                    .hasValueSatisfying(
                            excerpt -> assertThat(excerpt).isNotEmpty().hasSizeLessThan(1_024));
        }
    }

    @Test
    void keepsAResponseThatIsNotUtf8AsText() {
        byte[] latin1 = {'c', 'a', 'f', (byte) 0xE9, 0, '!'};
        byte[] cut = Arrays.copyOf("é".getBytes(StandardCharsets.UTF_8), 1);

        assertThat(WebhookClient.text(latin1)).isEqualTo("caf\uFFFD!");
        assertThat(WebhookClient.text(cut)).isEqualTo("\uFFFD");
    }

    private WebhookClient client(HostResolver dns, Predicate<InetAddress> allowed) {
        return new WebhookClient(
                new CheckedDnsResolver(dns, allowed), receiver.trust(), WebhookClient.Timeouts.DEFAULT, 4);
    }
}
