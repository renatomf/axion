package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Brackets in a query, as in {@code created[gte]}, reach the application unencoded, as a client
 * such as {@code curl -g} sends them. The request is written to a socket by hand: an HTTP client
 * would encode them, and MockMvc does not run the server that refused them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class QueryBracketsTest {

    @LocalServerPort
    private int port;

    @Test
    void passesUnencodedBracketsToTheApplication() throws IOException {
        String request = """
                GET /v1/charges?created[gte]=2026-11-22T00:00:00Z HTTP/1.1\r
                Host: localhost\r
                Connection: close\r
                \r
                """;
        String response;
        try (Socket socket = new Socket("localhost", port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
        }

        // The application's answer to a request with no key, not the server's refusal of the request line.
        assertThat(response).startsWith("HTTP/1.1 401").contains("application/problem+json");
    }
}
