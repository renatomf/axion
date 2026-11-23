package dev.axion.core.webhooks;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import dev.axion.core.webhooks.domain.HostResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A tenant's server for the tests: HTTPS on the loopback address, with a certificate for {@value
 * #HOST} issued when it starts, recording every request it receives. Each path answers as it was
 * told, {@code 204} unless told otherwise.
 */
public final class TestReceiver implements AutoCloseable {

    public static final String HOST = "receiver.example.com";

    /** A request as it arrived; header names in lowercase, the body as UTF-8 text. */
    public record Request(String path, Map<String, String> headers, String body) {}

    /** How a path answers. */
    public sealed interface Answer {

        record Status(int code) implements Answer {}

        record Late(Duration delay, int code) implements Answer {}

        record Redirect(String location) implements Answer {}
    }

    private final HttpsServer server;
    private final SSLContext trust;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();

    private TestReceiver(HttpsServer server, SSLContext trust) {
        this.server = server;
        this.trust = trust;
    }

    public static TestReceiver start() {
        try {
            KeyPair keys = KeyPairGenerator.getInstance("EC").generateKeyPair();
            X509Certificate certificate = certificate(keys);
            var server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverContext(keys, certificate)));
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            var receiver = new TestReceiver(server, clientContext(certificate));
            server.createContext("/", receiver::handle);
            server.start();
            return receiver;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("Cannot issue the receiver's certificate", e);
        }
    }

    /** A URL of this receiver, by its name. */
    public URI url(String path) {
        return URI.create("https://" + HOST + ":" + server.getAddress().getPort() + path);
    }

    public void answer(String path, Answer answer) {
        answers.put(path, answer);
    }

    public List<Request> requests(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    /** Trusts this receiver's certificate, as a client's trust store trusts a real one. */
    public SSLContext trust() {
        return trust;
    }

    /** Resolves {@value #HOST} and any name under it to the loopback address, and nothing else. */
    public static HostResolver loopbackDns() {
        return host -> {
            if (!host.endsWith(HOST)) {
                throw new UnknownHostException(host);
            }
            return List.of(InetAddress.getByAddress(host, new byte[] {127, 0, 0, 1}));
        };
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = exchange.getRequestHeaders().entrySet().stream()
                    .collect(Collectors.toMap(
                            header -> header.getKey().toLowerCase(Locale.ROOT),
                            header -> String.join(",", header.getValue())));
            requests.add(new Request(
                    path, headers, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            switch (answers.getOrDefault(path, new Answer.Status(204))) {
                case Answer.Status(int code) -> exchange.sendResponseHeaders(code, -1);
                case Answer.Late(Duration delay, int code) -> {
                    sleep(delay);
                    exchange.sendResponseHeaders(code, -1);
                }
                case Answer.Redirect(String location) -> {
                    exchange.getResponseHeaders().set("Location", location);
                    exchange.sendResponseHeaders(302, -1);
                }
            }
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static X509Certificate certificate(KeyPair keys)
            throws GeneralSecurityException, OperatorCreationException {
        var name = new X500Name("CN=" + HOST);
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(now.toEpochMilli()),
                Date.from(now.minus(Duration.ofMinutes(1))),
                Date.from(now.plus(Duration.ofDays(1))),
                name,
                keys.getPublic());
        try {
            builder.addExtension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, HOST)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var signer = new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static SSLContext serverContext(KeyPair keys, X509Certificate certificate)
            throws GeneralSecurityException, IOException {
        char[] password = "receiver".toCharArray();
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setKeyEntry("receiver", keys.getPrivate(), password, new X509Certificate[] {certificate});
        var keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    private static SSLContext clientContext(X509Certificate certificate) throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, null);
        store.setCertificateEntry("receiver", certificate);
        var trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }
}
