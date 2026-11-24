package dev.axion.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.shared.Environment;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.TestReceiver;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.EventTypes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The fault-injection scenarios of webhooks (events and webhooks design, section 13): a core killed
 * after a settlement's commit still delivers its webhook once it starts again, and endpoints that
 * time out do not slow settlement. The core runs as a process of its own, which the suite kills and
 * starts again on the same database, and receives the payments of the rails simulator's image;
 * tenants register their endpoints and create their charges through its API, as clients do.
 *
 * <p>This JVM's own context only creates the tenants and their keys. Its jobs are off, as in every
 * test, so it never touches what the core's process leaves in the database.
 *
 * <p>Run with {@code ./gradlew faultInjectionTest} after {@code just image rails-simulator}.
 */
@Tag(FaultInjectionTest.TAG)
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebhookFaultInjectionTest {

    private static final int CORE_PORT = RailsSimulator.freePort();

    private static final RailsSimulator RAILS = RailsSimulator.start(CORE_PORT);

    private static final TestReceiver RECEIVER = TestReceiver.startLocal();

    /** How long a worker holds a delivery it claimed (events and webhooks design, section 8.2). */
    private static final Duration LEASE = Duration.ofSeconds(30);

    /** The response timeout of a webhook's request (events and webhooks design, section 7.2). */
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    /** The most webhook requests the core has in flight at once (section 8.2). */
    private static final int IN_FLIGHT = 20;

    /** The payments of each phase of the latency scenario. */
    private static final int LATENCY_PAYMENTS = 20;

    /**
     * How much slower settlement may be while endpoints time out: two passes of the rails' processor,
     * every 500 ms, for the noise of a shared machine. Waiting on a webhook would cost the timeout.
     */
    private static final Duration LATENCY_MARGIN = Duration.ofSeconds(1);

    /** Long enough for a notification to be settled and its webhook delivered. */
    private static final Duration PATIENCE = Duration.ofSeconds(30);

    private final TenancyApi tenancy;
    private final AccessApi access;
    private final DatabaseOwner owner;
    private final CoreProcess core;
    private final HttpClient http = HttpClient.newHttpClient();

    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    WebhookFaultInjectionTest(
            TenancyApi tenancy,
            AccessApi access,
            DatabaseOwner owner,
            PostgreSQLContainer postgres,
            JdbcConnectionDetails service,
            @Value("${axion.members.issuer}") String issuer,
            @Value("${axion.webhooks.secrets-key}") String secretsKey) {
        this.tenancy = tenancy;
        this.access = access;
        this.owner = owner;
        this.core = CoreProcess.of(
                CORE_PORT,
                Map.of(
                        "spring.datasource.url", service.getJdbcUrl(),
                        "spring.datasource.username", service.getUsername(),
                        "spring.datasource.password", String.valueOf(service.getPassword()),
                        "spring.flyway.user", postgres.getUsername(),
                        "spring.flyway.password", postgres.getPassword(),
                        "axion.members.issuer", issuer,
                        "axion.rails.signing.keys[0].id", RAILS.keyId(),
                        "axion.rails.signing.keys[0].public-key", RAILS.publicKey(),
                        "axion.webhooks.secrets-key", secretsKey,
                        "axion.webhooks.allowed-local-ports", String.valueOf(RECEIVER.port())));
    }

    @BeforeAll
    void startCore() {
        core.start();
    }

    @AfterAll
    void stopCore() {
        core.close();
        RECEIVER.close();
    }

    @BeforeEach
    void createTenant() {
        organization = tenancy.createOrganization("Acme", "subject-" + UUID.randomUUID());
        key = AccessFixtures.issue(
                access,
                AccessFixtures.admin(organization, Environment.TEST),
                "Fault injection",
                Set.of(Permission.CHARGES_WRITE, Permission.CHARGES_READ, Permission.WEBHOOKS_MANAGE));
    }

    /**
     * The core is killed after the settlement's transaction commits and before the webhooks' listener
     * records the event: the listener waits on a lock the test holds on the events table. The
     * publication stays incomplete in the registry, the core hands it back to the listener when it
     * starts again, and the webhook is delivered once.
     */
    @Test
    void deliversAfterTheCoreIsKilledBeforeRecordingTheEvent() throws InterruptedException {
        String path = "/killed-before-recording";
        endpoint(path, "charge.paid");
        String brCode = charge();
        await(() -> events("charge.created"), recorded -> recorded == 1);

        int listener;
        try (var held = owner.session()) {
            held.jdbc().sql("LOCK TABLE webhooks.events IN EXCLUSIVE MODE").update();
            int holder = held.jdbc()
                    .sql("SELECT pg_backend_pid()")
                    .query(Integer.class)
                    .single();

            RAILS.pay(brCode);

            await(this::paidCharges, paid -> paid == 1);
            listener = await(() -> blockedBy(holder), blocked -> !blocked.isEmpty())
                    .getFirst();
            core.kill();
        }
        // Released, the killed core's insert ends, and its session finds the connection gone and rolls back.
        await(() -> backendAlive(listener), alive -> !alive);
        assertThat(events("charge.paid"))
                .as("events recorded by the killed core")
                .isZero();
        assertThat(RECEIVER.requests(path)).isEmpty();

        core.start();

        await(() -> deliveries("charge.paid"), List.of("SUCCEEDED")::equals);
        assertThat(RECEIVER.requests(path)).singleElement().satisfies(request -> {
            assertThat((String) JsonPath.read(request.body(), "$.type")).isEqualTo("charge.paid");
            assertThat((String) JsonPath.read(request.body(), "$.data.object.status"))
                    .isEqualTo("paid");
        });
    }

    /**
     * The core is killed while the receiver holds the webhook's request, after the settlement and the
     * delivery committed: the attempt is never recorded. The lease the killed core took passes, the
     * core started again claims the delivery and sends the same event, which the receiver tells
     * apart from a new one by its {@code webhook-id}.
     */
    @Test
    void deliversAgainAfterTheCoreIsKilledDuringTheRequest() throws InterruptedException {
        String path = "/killed-during-request";
        RECEIVER.answer(path, new TestReceiver.Answer.Late(Duration.ofMinutes(5), 204));
        endpoint(path, "charge.paid");

        RAILS.pay(charge());

        await(() -> RECEIVER.requests(path), requests -> requests.size() == 1);
        core.kill();
        RECEIVER.answer(path, new TestReceiver.Answer.Status(204));
        core.start();

        List<TestReceiver.Request> requests =
                await(LEASE.plus(PATIENCE), () -> RECEIVER.requests(path), sent -> sent.size() == 2);
        assertThat(requests.getLast().header("webhook-id"))
                .isEqualTo(requests.getFirst().header("webhook-id"));
        assertThat(requests.getLast().body()).isEqualTo(requests.getFirst().body());
        await(() -> deliveries("charge.paid"), List.of("SUCCEEDED")::equals);
        assertThat(attempts("charge.paid")).as("attempts recorded").isOne();
    }

    /**
     * Settlement does not wait on webhooks: with every endpoint of the tenant timing out, and the
     * workers' requests all waiting on them, credits settle as fast as they do with no endpoint at
     * all. Latency is the core's own, from receiving a credit to settling it, so the rails' delivery
     * does not count.
     */
    @Test
    void settlesAtTheSamePaceWhileEveryEndpointTimesOut() throws InterruptedException {
        Duration alone = p95(settle(charges(LATENCY_PAYMENTS)));

        String path = "/timing-out";
        RECEIVER.answer(path, new TestReceiver.Answer.Late(WEBHOOK_TIMEOUT.plusSeconds(5), 204));
        List<String> endpoints = new ArrayList<>();
        try {
            for (int i = 0; i < WebhooksApi.MAX_ENDPOINTS; i++) {
                endpoints.add(endpoint(path, EventTypes.ALL));
            }
            List<String> brCodes = charges(LATENCY_PAYMENTS);
            await(() -> RECEIVER.requests(path), requests -> requests.size() >= IN_FLIGHT);
            Instant saturated = Instant.now();

            Duration timingOut = p95(settle(brCodes));

            assertThat(Duration.between(saturated, Instant.now()))
                    .as("the credits settled while every worker waited on an endpoint")
                    .isLessThan(WEBHOOK_TIMEOUT);
            assertThat(timingOut)
                    .as("settlement's p95 while every endpoint times out, against %s with none", alone)
                    .isLessThanOrEqualTo(alone.plus(LATENCY_MARGIN));
        } finally {
            endpoints.forEach(id -> send("DELETE", "/v1/webhook_endpoints/" + id, null, 204));
        }
    }

    /** Pays each code and waits until every credit settles; returns how long each took in the core. */
    private List<Duration> settle(List<String> brCodes) throws InterruptedException {
        List<String> endToEndIds = brCodes.stream().map(RAILS::pay).toList();
        return await(() -> settlementTimes(endToEndIds), times -> times.size() == endToEndIds.size());
    }

    private List<Duration> settlementTimes(List<String> endToEndIds) {
        return owner.jdbc()
                .sql("""
                        SELECT received_at, processed_at FROM rails.pix_credits
                         WHERE end_to_end_id IN (:ids) AND processed_at IS NOT NULL""")
                .param("ids", endToEndIds)
                .query((row, number) -> Duration.between(
                        row.getObject("received_at", OffsetDateTime.class),
                        row.getObject("processed_at", OffsetDateTime.class)))
                .list();
    }

    private static Duration p95(List<Duration> durations) {
        List<Duration> sorted = durations.stream().sorted().toList();
        return sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1);
    }

    private List<String> charges(int count) {
        List<String> brCodes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            brCodes.add(charge());
        }
        return brCodes;
    }

    /**
     * Registers an endpoint of the receiver's path for the event types, through the core's API;
     * returns its ID.
     */
    private String endpoint(String path, String... eventTypes) {
        String created = send(
                "POST",
                "/v1/webhook_endpoints",
                """
                {"url": "%s", "enabled_events": [%s]}""".formatted(
                                RECEIVER.url(path),
                                String.join(
                                        ", ",
                                        List.of(eventTypes).stream()
                                                .map(type -> '"' + type + '"')
                                                .toList())),
                201);
        return JsonPath.read(created, "$.id");
    }

    /** Creates a charge of R$ 100.00 through the core's API; returns its BR Code. */
    private String charge() {
        String created = send("POST", "/v1/charges", """
                {"amount": 10000, "currency": "BRL"}""", 201);
        return JsonPath.read(created, "$.pix.br_code");
    }

    private String send(String method, String path, @Nullable String body, int expected) {
        var request = HttpRequest.newBuilder(core.uri(path))
                .header("Authorization", "Bearer " + key.secret())
                .header("Idempotency-Key", UUID.randomUUID().toString())
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
            throw new AssertionError("The core answered %s %s with %d, not %d: %s"
                    .formatted(method, path, response.statusCode(), expected, response.body()));
        }
        return response.body();
    }

    /** The statuses of the tenant's deliveries of the event type, read as the owner. */
    private List<String> deliveries(String eventType) {
        return owner.jdbc()
                .sql("""
                        SELECT d.status FROM webhooks.deliveries d
                          JOIN webhooks.events e ON e.id = d.event_id
                         WHERE d.tenant_id = ? AND e.type = ?
                         ORDER BY d.created_at""")
                .params(organization.test().value(), eventType)
                .query((row, number) -> row.getString("status"))
                .list();
    }

    /** The attempts recorded of the tenant's deliveries of the event type. */
    private long attempts(String eventType) {
        return owner.jdbc()
                .sql("""
                        SELECT coalesce(sum(d.attempt_count), 0) FROM webhooks.deliveries d
                          JOIN webhooks.events e ON e.id = d.event_id
                         WHERE d.tenant_id = ? AND e.type = ?""")
                .params(organization.test().value(), eventType)
                .query(Long.class)
                .single();
    }

    /** The tenant's public events of the type, recorded for delivery. */
    private long events(String eventType) {
        return owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.events WHERE tenant_id = ? AND type = ?")
                .params(organization.test().value(), eventType)
                .query(Long.class)
                .single();
    }

    private long paidCharges() {
        return owner.jdbc()
                .sql("SELECT count(*) FROM billing.charges WHERE tenant_id = ? AND status = 'PAID'")
                .param(organization.test().value())
                .query(Long.class)
                .single();
    }

    /** The database sessions waiting on a lock the session {@code holder} holds. */
    private List<Integer> blockedBy(int holder) {
        return owner.jdbc()
                .sql("SELECT pid FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid))")
                .param(holder)
                .query((row, number) -> row.getInt("pid"))
                .list();
    }

    private boolean backendAlive(int pid) {
        return owner.jdbc()
                .sql("SELECT EXISTS (SELECT FROM pg_stat_activity WHERE pid = ?)")
                .param(pid)
                .query(Boolean.class)
                .single();
    }

    /** Polls until the value satisfies the condition, then returns it; fails after {@link #PATIENCE}. */
    private static <T> T await(Supplier<T> value, Predicate<T> condition) throws InterruptedException {
        return await(PATIENCE, value, condition);
    }

    private static <T> T await(Duration patience, Supplier<T> value, Predicate<T> condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(patience);
        while (true) {
            T now = value.get();
            if (condition.test(now)) {
                return now;
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Still " + now + " after " + patience);
            }
            Thread.sleep(200);
        }
    }
}
