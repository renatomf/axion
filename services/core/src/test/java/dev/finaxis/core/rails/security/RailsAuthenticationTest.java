package dev.finaxis.core.rails.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.rails.RailsClient;
import dev.finaxis.core.rails.RailsSigner;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The filter chain of {@code /rails/**}, on the running server. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class RailsAuthenticationTest {

    // No endpoint is mapped here, so a verified request is answered 404 once it leaves the chain.
    private static final String PATH = "/rails/unmapped";

    private static final RailsSigner RAILS = new RailsSigner("rails-test");

    private static final byte[] BODY = "{\"messageType\":\"pacs.008\"}".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("finaxis.rails.signing.keys[0].id", RAILS::keyId);
        registry.add("finaxis.rails.signing.keys[0].public-key", RAILS::publicKey);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Clock clock;

    private RailsClient rails;

    @BeforeEach
    void connect() {
        rails = new RailsClient(port, RAILS);
    }

    @Test
    void letsASignedRequestThrough() {
        assertThat(rails.post(PATH, BODY, clock.instant()).statusCode()).isEqualTo(404);
    }

    @Test
    void refusesAnUnsignedRequestWithNoDetail() {
        HttpResponse<String> response = rails.send("POST", PATH, RailsClient.JSON, BODY, Map.of());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body())
                .contains("\"detail\":\"Authentication is required.\"")
                .contains("\"code\":\"unauthorized\"");
    }

    @Test
    void refusesASignedRequestWhoseBodyChanged() {
        var headers = RAILS.sign("POST", PATH, RailsClient.JSON, BODY, clock.instant());
        byte[] changed = "{\"messageType\":\"pacs.009\"}".getBytes(StandardCharsets.UTF_8);

        assertThat(rails.send("POST", PATH, RailsClient.JSON, changed, headers).statusCode())
                .isEqualTo(401);
    }

    @Test
    void refusesAStaleSignature() {
        assertThat(rails.post(PATH, BODY, clock.instant().minus(Duration.ofMinutes(6)))
                        .statusCode())
                .isEqualTo(401);
    }

    // The access module's API keys open nothing here.
    @Test
    void refusesAnApiKey() {
        var apiKey = Map.of("Authorization", "Bearer fx_test_" + "a".repeat(40));

        assertThat(rails.send("POST", PATH, RailsClient.JSON, BODY, apiKey).statusCode())
                .isEqualTo(401);
    }

    @Test
    void acceptsABodyOfSixteenKibibytes() {
        byte[] body = new byte[SignedMessageFilter.MAX_BODY];

        assertThat(rails.post(PATH, body, clock.instant()).statusCode()).isEqualTo(404);
    }

    @Test
    void refusesALargerBodyBeforeVerifyingIt() {
        HttpResponse<String> response = rails.post(PATH, new byte[SignedMessageFilter.MAX_BODY + 1], clock.instant());

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("\"detail\":\"A request body is at most 16 KiB.\"");
    }
}
