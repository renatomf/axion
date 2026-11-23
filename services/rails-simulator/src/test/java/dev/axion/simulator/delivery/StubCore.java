package dev.axion.simulator.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The core's endpoint for the delivery tests: records every request and answers {@code 200}, or the
 * replies queued for a transaction ID, so each test drives its own payments without seeing the others'.
 */
final class StubCore implements AutoCloseable {

    private static final Pattern TRANSACTION_ID = Pattern.compile("\"transactionId\":\"([A-Za-z0-9]+)\"");

    /** A request as the stub received it; header names in lower case. */
    record Received(String method, String path, Map<String, String> headers, String body) {

        /** The header's value; a request without it fails the test. */
        String header(String name) {
            String value = headers.get(name);
            if (value == null) {
                throw new AssertionError("The request has no " + name + " header");
            }
            return value;
        }

        String transactionId() {
            Matcher matcher = TRANSACTION_ID.matcher(body);
            return matcher.find() ? matcher.group(1) : "";
        }
    }

    /** An answer: a status, its headers, and a wait before it is sent. */
    record Reply(int status, Map<String, String> headers, Duration delay) {

        static Reply status(int status) {
            return new Reply(status, Map.of(), Duration.ZERO);
        }
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Map<String, Queue<Reply>> replies = new ConcurrentHashMap<>();

    StubCore() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Answers the next requests for this transaction ID with these replies, in order, then with {@code 200}. */
    void reply(String transactionId, Reply... answers) {
        replies.computeIfAbsent(transactionId, id -> new ArrayDeque<>()).addAll(List.of(answers));
    }

    List<Received> receivedFor(String transactionId) {
        return received.stream()
                .filter(request -> request.transactionId().equals(transactionId))
                .toList();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var request = new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getRawPath(),
                    exchange.getRequestHeaders().entrySet().stream()
                            .collect(Collectors.toMap(
                                    header -> header.getKey().toLowerCase(Locale.ROOT),
                                    header -> String.join(",", header.getValue()))),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            received.add(request);
            Reply reply = next(request.transactionId());
            sleep(reply.delay());
            reply.headers().forEach(exchange.getResponseHeaders()::add);
            exchange.sendResponseHeaders(reply.status(), -1);
        }
    }

    private Reply next(String transactionId) {
        Queue<Reply> queued = replies.get(transactionId);
        synchronized (this) {
            @Nullable Reply reply = queued == null ? null : queued.poll();
            return reply == null ? Reply.status(200) : reply;
        }
    }

    private static void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
