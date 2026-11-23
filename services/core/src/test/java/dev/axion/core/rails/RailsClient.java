package dev.axion.core.rails;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;

/**
 * Sends requests to the core's running server as the rails do. A real server, not MockMvc: the
 * signature covers the {@code Content-Type} header, which MockMvc rewrites when the request's
 * character encoding is set.
 */
public final class RailsClient {

    public static final String JSON = "application/json";

    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    private final RailsSigner signer;

    public RailsClient(int port, RailsSigner signer) {
        this.port = port;
        this.signer = signer;
    }

    /** Signs the body at {@code created} and sends it. */
    public HttpResponse<String> post(String path, byte[] body, Instant created) {
        return send("POST", path, JSON, body, signer.sign("POST", path, JSON, body, created));
    }

    /** Sends the request with the given signature headers, whatever they signed. */
    public HttpResponse<String> send(
            String method, String path, String contentType, byte[] body, RailsSigner.Headers headers) {
        return send(
                method,
                path,
                contentType,
                body,
                Map.of(
                        "Content-Digest", headers.contentDigest(),
                        "Signature-Input", headers.signatureInput(),
                        "Signature", headers.signature()));
    }

    public HttpResponse<String> send(
            String method, String path, String contentType, byte[] body, Map<String, String> headers) {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .header("Content-Type", contentType);
        headers.forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
