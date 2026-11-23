package dev.finaxis.core;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The rails simulator's image, run with a database of its own and driven through its operator API
 * (rails simulator design, sections 7 and 8). It signs with a key pair generated for this run and
 * delivers to a core on the host, at the port given.
 *
 * <p>The image is built from the repository, not pulled: {@code just image rails-simulator} tags it
 * {@value #IMAGE_NAME}, and CI builds it before the suite runs.
 */
public final class RailsSimulator {

    static final String IMAGE_NAME = "finaxis-rails-simulator:dev";

    private static final int OPERATOR_PORT = 8090;
    private static final String DATABASE_ALIAS = "rails-database";
    private static final String DATABASE = "rails_simulator";
    private static final String KEY_ID = "rails-fault-injection";

    private final KeyPair signingKey;
    private final String operatorToken;
    private final URI operator;
    private final HttpClient http = HttpClient.newHttpClient();

    private RailsSimulator(KeyPair signingKey, String operatorToken, URI operator) {
        this.signingKey = signingKey;
        this.operatorToken = operatorToken;
        this.operator = operator;
    }

    /** Starts the simulator, delivering to a core that will listen on {@code corePort} of the host. */
    public static RailsSimulator start(int corePort) {
        KeyPair signingKey = ed25519();
        String operatorToken = "fault-injection-" + UUID.randomUUID();
        Network network = Network.newNetwork();
        PostgreSQLContainer database = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE)
                .withDatabaseName(DATABASE)
                .withUsername(DATABASE)
                .withNetwork(network)
                .withNetworkAliases(DATABASE_ALIAS);
        database.start();

        // Before the container starts: the simulator reaches the host as host.testcontainers.internal.
        Testcontainers.exposeHostPorts(corePort);
        GenericContainer<?> simulator = new GenericContainer<>(DockerImageName.parse(IMAGE_NAME))
                .withNetwork(network)
                .withEnv("SPRING_DATASOURCE_URL", "jdbc:postgresql://" + DATABASE_ALIAS + ":5432/" + DATABASE)
                .withEnv("SPRING_DATASOURCE_USERNAME", database.getUsername())
                .withEnv("SPRING_DATASOURCE_PASSWORD", database.getPassword())
                .withEnv("RAILS_SIMULATOR_CORE_URL", "http://host.testcontainers.internal:" + corePort)
                .withEnv("RAILS_SIMULATOR_OPERATOR_TOKEN", operatorToken)
                .withEnv("RAILS_SIMULATOR_SIGNING_KEY_ID", KEY_ID)
                .withEnv(
                        "RAILS_SIMULATOR_SIGNING_PRIVATE_KEY",
                        base64(signingKey.getPrivate().getEncoded()))
                .withExposedPorts(OPERATOR_PORT)
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("rails-simulator")))
                .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(OPERATOR_PORT))
                .withStartupTimeout(Duration.ofMinutes(2));
        simulator.start();

        URI operator = URI.create("http://" + simulator.getHost() + ":" + simulator.getMappedPort(OPERATOR_PORT));
        return new RailsSimulator(signingKey, operatorToken, operator);
    }

    /** A port free on this machine now, for the core to listen on. */
    public static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The ID of the simulator's signing key, as the core lists it. */
    public String keyId() {
        return KEY_ID;
    }

    /** The simulator's public key, X.509 SubjectPublicKeyInfo DER in base64, as the core lists it. */
    public String publicKey() {
        return base64(signingKey.getPublic().getEncoded());
    }

    /** Pays the BR Code as a payer would; returns the payment's {@code EndToEndId}. */
    public String pay(String brCode) {
        String created = send("POST", "/pix/payments", """
                {"brCode": "%s"}""".formatted(brCode), 201);
        return JsonPath.read(created, "$.endToEndId");
    }

    /** The payment and every delivery of its notification, as the payer API answers it. */
    public String payment(String endToEndId) {
        return send("GET", "/pix/payments/" + endToEndId, null, 200);
    }

    /** Adds a fault rule, the body of {@code POST /chaos/rules}. */
    public void addRule(String rule) {
        send("POST", "/chaos/rules", rule, 201);
    }

    /** Takes the rails down for the duration. */
    public void startOutage(Duration duration) {
        send("PUT", "/chaos/outage", """
                {"duration": "%s"}""".formatted(seconds(duration)), 200);
    }

    /** The duration as the operator API reads it, in seconds: {@code PT120S}, not {@code PT2M}. */
    public static String seconds(Duration duration) {
        return "PT" + duration.toSeconds() + "S";
    }

    /** Removes every fault rule and ends any outage, so the next test starts without chaos. */
    public void reset() {
        send("DELETE", "/chaos/rules", null, 204);
        send("DELETE", "/chaos/outage", null, 204);
    }

    private String send(String method, String path, @Nullable String body, int expected) {
        var request = HttpRequest.newBuilder(operator.resolve(path))
                .header("Authorization", "Bearer " + operatorToken)
                .header("Content-Type", "application/json")
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (response.statusCode() != expected) {
            throw new AssertionError("The simulator answered %s %s with %d, not %d: %s"
                    .formatted(method, path, response.statusCode(), expected, response.body()));
        }
        return response.body();
    }

    private static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
