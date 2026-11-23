package dev.axion.core.rails.security;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.rails.RailsClient;
import dev.axion.core.rails.RailsSigner;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The signing key's request budget on {@code /rails/**}, on the running server, with a clock that
 * stands still so no token refills during the test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestConfiguration.class, RailsRateLimitingTest.Configuration.class})
class RailsRateLimitingTest {

    private static final String PATH = "/rails/unmapped";

    private static final RailsSigner RAILS = new RailsSigner("rails-limited");

    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("finaxis.rails.signing.keys[0].id", RAILS::keyId);
        registry.add("finaxis.rails.signing.keys[0].public-key", RAILS::publicKey);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuration {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(Instant.parse("2026-11-21T14:30:00Z"));
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private MutableClock clock;

    private RailsClient rails;

    @BeforeEach
    void connect() {
        rails = new RailsClient(port, RAILS);
    }

    @Test
    void refusesAKeyPastItsBurstWithRetryAfter() {
        IntStream.range(0, (int) SigningKeyRateLimits.BURST).forEach(_ -> rails.post(PATH, BODY, clock.instant()));

        HttpResponse<String> refused = rails.post(PATH, BODY, clock.instant());

        assertThat(refused.statusCode()).isEqualTo(429);
        assertThat(refused.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(refused.body()).contains("\"code\":\"rate_limited\"");
        // An unsigned request is refused for what it is, whatever the key's budget.
        assertThat(rails.send("POST", PATH, RailsClient.JSON, BODY, Map.of()).statusCode())
                .isEqualTo(401);
    }
}
