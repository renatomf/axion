package dev.axion.core.webhooks.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.MutableClock;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.billing.BillingApi;
import dev.axion.core.billing.BillingFixtures;
import dev.axion.core.billing.ChargeRequest;
import dev.axion.core.events.CommittedEvents;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.CreatedEndpoint;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.RollSecretResult;
import dev.axion.core.webhooks.TestDeliveries;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.TestReceiver;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.EndpointHealth;
import dev.axion.core.webhooks.domain.RetryPolicy;
import dev.axion.core.webhooks.domain.SigningSecret;
import dev.axion.core.webhooks.domain.WebhookSignature;
import dev.axion.core.webhooks.http.CheckedDnsResolver;
import dev.axion.core.webhooks.http.WebhookClient;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link DeliveryQueue} against a real PostgreSQL and a local HTTPS receiver (events and webhooks
 * design, sections 8.2, 8.3 and 8.5). The tests' clock stands in a past no other test uses, so a
 * pass claims only the deliveries these tests made due; tests of the retries move it forward.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class, CommittedEvents.class, DeliveryQueueTest.Receiving.class})
class DeliveryQueueTest {

    private static final Instant START = Instant.parse("2025-06-02T12:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class Receiving {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        // No jitter, so each retry is due exactly when the schedule says.
        @Bean
        @Primary
        RetryPolicy exactRetryPolicy() {
            return new RetryPolicy(new RandomGenerator() {
                @Override
                public long nextLong() {
                    throw new UnsupportedOperationException("The policy draws doubles only");
                }

                @Override
                public double nextDouble() {
                    return 0.5;
                }
            });
        }

        @Bean
        TestReceiver testReceiver() {
            return TestReceiver.start();
        }

        // The receiver's name resolves to the loopback address, which only these tests may reach.
        @Bean
        @Primary
        WebhookClient testWebhookClient(TestReceiver receiver, MutableClock clock) {
            return new WebhookClient(
                    new CheckedDnsResolver(TestReceiver.loopbackDns(), InetAddress::isLoopbackAddress),
                    receiver.trust(),
                    WebhookClient.Timeouts.DEFAULT,
                    DeliveryQueue.IN_FLIGHT);
        }
    }

    private final DeliveryQueue queue;
    private final WebhooksApi webhooks;
    private final TestReceiver receiver;
    private final MutableClock clock;
    private final JdbcClient owner;
    private final TestDeliveries deliveries;
    private final TenantId tenant = new TenantId(UUID.randomUUID());

    @Autowired
    DeliveryQueueTest(
            DeliveryQueue queue, WebhooksApi webhooks, TestReceiver receiver, MutableClock clock, DatabaseOwner owner) {
        this.queue = queue;
        this.webhooks = webhooks;
        this.receiver = receiver;
        this.clock = clock;
        this.owner = owner.jdbc();
        this.deliveries = new TestDeliveries(this.owner);
    }

    // What a test left pending would be claimed by the next one's passes.
    @AfterEach
    void cancelWhatIsLeft() {
        owner.sql("""
                        UPDATE webhooks.deliveries SET status = 'CANCELED', next_attempt_at = NULL, leased_until = NULL
                         WHERE status = 'PENDING' AND next_attempt_at < '2026-01-01'
                        """).update();
    }

    @Test
    void sendsADueDeliveryAsStoredAndRecordsItsSuccess() throws Exception {
        String path = "/" + UUID.randomUUID();
        WebhookEndpointId endpoint = endpoint(path);
        UUID delivery = deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(receiver.requests(path)).singleElement().satisfies(request -> {
            assertThat(request.body()).isEqualTo(deliveries.body(delivery));
            assertThat(request.headers()).containsKey("webhook-id");
        });
        assertThat(deliveries.status(delivery)).isEqualTo("SUCCEEDED");
        assertThat(deliveries.attemptCount(delivery)).isOne();
        assertThat(deliveries.leased(delivery)).isFalse();
    }

    @Test
    void signsTheRequestWithTheEndpointsSecretAtTheAttemptsTime() throws Exception {
        String path = "/" + UUID.randomUUID();
        var created = create(tenant, path, Set.of("*"));
        Instant now = clock.instant();
        UUID delivery = deliveries.pending(tenant, created.endpoint().id(), now, null);

        drainAndWait();

        assertThat(receiver.requests(path)).singleElement().satisfies(request -> {
            assertThat(request.headers()).containsEntry("webhook-timestamp", String.valueOf(now.getEpochSecond()));
            assertThat(request.header("webhook-signature")).isEqualTo(signature(request, created.secret()));
        });
        assertThat(deliveries.status(delivery)).isEqualTo("SUCCEEDED");
    }

    @Test
    void signsWithTheNewSecretFirstAndTheRolledOneDuringItsGracePeriod() throws Exception {
        String path = "/" + UUID.randomUUID();
        var created = create(tenant, path, Set.of("*"));
        WebhookEndpointId endpoint = created.endpoint().id();
        var rolled = (RollSecretResult.Rolled)
                TestCaller.call(tenant, Environment.TEST, () -> webhooks.rollSecret(endpoint, Duration.ofHours(1)));
        // The tests' clock stands in the past; the policy that shows a rolled secret compares its end
        // with the database's own time.
        owner.sql("UPDATE webhooks.secrets SET expires_at = now() + interval '1 hour' WHERE endpoint_id = ?"
                        + " AND expires_at IS NOT NULL")
                .param(endpoint.value())
                .update();
        deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(receiver.requests(path))
                .singleElement()
                .satisfies(request -> assertThat(
                                request.header("webhook-signature").split(" "))
                        .containsExactly(
                                signature(request, rolled.secret().secret()), signature(request, created.secret())));
    }

    @Test
    void sendsNothingItCannotSign() throws Exception {
        String path = "/" + UUID.randomUUID();
        WebhookEndpointId endpoint = endpoint(path);
        owner.sql("UPDATE webhooks.secrets SET ciphertext = '\\x00' WHERE endpoint_id = ?")
                .param(endpoint.value())
                .update();
        UUID delivery = deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(receiver.requests(path)).isEmpty();
        assertThat(deliveries.status(delivery)).isEqualTo("PENDING");
        assertThat(deliveries.leased(delivery)).isTrue();
    }

    @Test
    void attemptsAFailedDeliveryAgainAMinuteLater() throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(500));
        Instant now = clock.instant();
        UUID delivery = deliveries.pending(tenant, endpoint(path), now, null);

        drainAndWait();

        assertThat(deliveries.status(delivery)).isEqualTo("PENDING");
        assertThat(deliveries.attemptCount(delivery)).isOne();
        assertThat(deliveries.nextAttemptAt(delivery)).contains(now.plus(Duration.ofMinutes(1)));
        assertThat(deliveries.leased(delivery)).isFalse();
    }

    @Test
    void followsTheScheduleUntilTheNinthFailureEndsTheDelivery() throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(503));
        UUID delivery = deliveries.pending(tenant, endpoint(path), clock.instant(), null);
        Instant first = clock.instant();

        drainAndWait();
        for (int attempt = 2; attempt <= RetryPolicy.ATTEMPTS; attempt++) {
            Instant due = deliveries.nextAttemptAt(delivery).orElseThrow();
            clock.advance(Duration.between(clock.instant(), due).minusSeconds(1));
            drainAndWait();
            assertThat(deliveries.attemptCount(delivery))
                    .as("attempts before it is due")
                    .isEqualTo(attempt - 1);
            clock.advance(Duration.ofSeconds(1));
            drainAndWait();
        }

        assertThat(deliveries.status(delivery)).isEqualTo("DEAD");
        assertThat(deliveries.attemptCount(delivery)).isEqualTo(RetryPolicy.ATTEMPTS);
        assertThat(receiver.requests(path).stream()
                        .map(request -> Long.parseLong(request.header("webhook-timestamp")) - first.getEpochSecond()))
                .containsExactly(0L, 60L, 360L, 2_160L, 9_360L, 30_960L, 74_160L, 160_560L, 246_960L);
    }

    @Test
    void endsADeliveryAtOnceWhenTheReceiverAnswersGone() throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(410));
        UUID delivery = deliveries.pending(tenant, endpoint(path), clock.instant(), null);

        drainAndWait();

        assertThat(deliveries.status(delivery)).isEqualTo("DEAD");
        assertThat(deliveries.attemptCount(delivery)).isOne();
    }

    @Test
    void countsAFailedAttemptOnItsEndpointFromTheFirst() throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(500));
        WebhookEndpointId endpoint = endpoint(path);
        Instant first = clock.instant();
        deliveries.pending(tenant, endpoint, first, null);
        drainAndWait();
        clock.advance(Duration.ofSeconds(30));
        deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(deliveries.health(endpoint)).isEqualTo(new EndpointHealth(Optional.of(first), 2));
    }

    @Test
    void clearsTheEndpointsFailuresWithASuccess() throws Exception {
        String path = "/" + UUID.randomUUID();
        WebhookEndpointId endpoint = endpoint(path);
        deliveries.failing(endpoint, clock.instant().minus(Duration.ofHours(5)), 7);
        deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(deliveries.health(endpoint)).isEqualTo(EndpointHealth.HEALTHY);
    }

    @Test
    void disablesAnEndpointFailingForThreeDaysWhoseLast20AttemptsFailed(@Autowired CommittedEvents events)
            throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(500));
        WebhookEndpointId endpoint = endpoint(path);
        Instant failingSince = clock.instant().minus(EndpointHealth.FAILING_FOR);
        deliveries.failing(endpoint, failingSince, EndpointHealth.FAILED_ATTEMPTS - 1);
        UUID failing = deliveries.pending(tenant, endpoint, clock.instant(), null);
        UUID waiting = deliveries.pending(tenant, endpoint, clock.instant().plusSeconds(60), null);

        drainAndWait();

        EndpointView disabled = TestCaller.call(tenant, Environment.TEST, () -> webhooks.endpoint(endpoint))
                .orElseThrow();
        assertThat(disabled.status()).isEqualTo(EndpointStatus.DISABLED);
        assertThat(disabled.disabledReason()).contains(DisabledReason.FAILING);
        assertThat(deliveries.status(failing)).isEqualTo("CANCELED");
        assertThat(deliveries.status(waiting)).isEqualTo("CANCELED");
        assertThat(events.of(tenant, WebhookEndpointDisabled.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.endpoint()).isEqualTo(disabled);
                    assertThat(event.failingSince()).isEqualTo(failingSince);
                    assertThat(event.environment()).isEqualTo(Environment.TEST);
                    assertThat(event.actor()).isEqualTo(new Actor.Job("webhook-delivery"));
                });
    }

    @ParameterizedTest
    @CsvSource({"PT71H59M, 19", "PT71H59M, 500", "PT80H, 18"})
    void keepsAnEndpointEnabledUntilBothConditionsHold(Duration failingFor, int failedBefore) throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(500));
        WebhookEndpointId endpoint = endpoint(path);
        deliveries.failing(endpoint, clock.instant().minus(failingFor), failedBefore);
        UUID delivery = deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(TestCaller.call(tenant, Environment.TEST, () -> webhooks.endpoint(endpoint))
                        .orElseThrow()
                        .status())
                .isEqualTo(EndpointStatus.ENABLED);
        assertThat(deliveries.status(delivery)).isEqualTo("PENDING");
        assertThat(deliveries.health(endpoint).consecutiveFailures()).isEqualTo(failedBefore + 1);
    }

    @Test
    void keepsAnEndpointWhoseFailingAttemptSucceededEnabled() throws Exception {
        String path = "/" + UUID.randomUUID();
        WebhookEndpointId endpoint = endpoint(path);
        deliveries.failing(endpoint, clock.instant().minus(Duration.ofDays(4)), 500);
        deliveries.pending(tenant, endpoint, clock.instant(), null);

        drainAndWait();

        assertThat(TestCaller.call(tenant, Environment.TEST, () -> webhooks.endpoint(endpoint))
                        .orElseThrow()
                        .status())
                .isEqualTo(EndpointStatus.ENABLED);
        assertThat(deliveries.health(endpoint)).isEqualTo(EndpointHealth.HEALTHY);
    }

    @Test
    void sendsNothingBeforeItIsDue() throws Exception {
        String path = "/" + UUID.randomUUID();
        UUID delivery =
                deliveries.pending(tenant, endpoint(path), clock.instant().plusSeconds(60), null);

        drainAndWait();

        assertThat(receiver.requests(path)).isEmpty();
        assertThat(deliveries.status(delivery)).isEqualTo("PENDING");
    }

    @Test
    void claimsAgainADeliveryWhoseLeasePassedAndNoneStillHeld() throws Exception {
        String path = "/" + UUID.randomUUID();
        WebhookEndpointId endpoint = endpoint(path);
        Instant now = clock.instant();
        UUID dropped = deliveries.pending(tenant, endpoint, now.minusSeconds(60), now.minusSeconds(1));
        UUID held = deliveries.pending(tenant, endpoint, now.minusSeconds(60), now.plusSeconds(20));

        drainAndWait();

        assertThat(receiver.requests(path)).hasSize(1);
        assertThat(deliveries.status(dropped)).isEqualTo("SUCCEEDED");
        assertThat(deliveries.status(held)).isEqualTo("PENDING");
    }

    @Test
    void holdsAtMostFourDeliveriesOfOneEndpointAndServesTheOthersMeanwhile() throws Exception {
        String slowPath = "/" + UUID.randomUUID();
        receiver.answer(slowPath, new TestReceiver.Answer.Late(Duration.ofSeconds(2), 204));
        WebhookEndpointId slow = endpoint(slowPath);
        List<UUID> slowOnes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            slowOnes.add(deliveries.pending(tenant, slow, clock.instant().minusSeconds(60 - i), null));
        }
        String fastPath = "/" + UUID.randomUUID();
        UUID fast = deliveries.pending(tenant, endpoint(fastPath), clock.instant(), null);

        List<Future<?>> first = queue.drain();
        List<Future<?>> second = queue.drain();

        assertThat(first).hasSize(5);
        assertThat(second).isEmpty();
        await().atMost(Duration.ofSeconds(1))
                .until(() -> deliveries.status(fast).equals("SUCCEEDED"));
        assertThat(slowOnes.stream().filter(deliveries::leased)).hasSize(4);
        waitFor(first);
        assertThat(slowOnes.stream().map(deliveries::status))
                .containsExactly("SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED", "PENDING", "PENDING");
    }

    @Test
    void sendsEachDeliveryOnceWhenPassesRunConcurrently() throws Exception {
        String path = "/" + UUID.randomUUID();
        List<WebhookEndpointId> endpoints = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            endpoints.add(endpoint(path));
        }
        List<UUID> due = new ArrayList<>();
        for (WebhookEndpointId endpoint : endpoints) {
            for (int i = 0; i < 4; i++) {
                due.add(deliveries.pending(tenant, endpoint, clock.instant(), null));
            }
        }

        List<Future<?>> sends = new ArrayList<>();
        try (var passes = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = passes.submit(queue::drain);
            var second = passes.submit(queue::drain);
            sends.addAll(first.get());
            sends.addAll(second.get());
        }
        waitFor(sends);

        assertThat(receiver.requests(path)).hasSize(16);
        assertThat(due.stream().map(deliveries::status)).containsOnly("SUCCEEDED");
    }

    @Test
    void dropsTheResultOfADeliveryCanceledDuringItsAttempt() throws Exception {
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Late(Duration.ofMillis(500), 500));
        WebhookEndpointId endpoint = endpoint(path);
        UUID delivery = deliveries.pending(tenant, endpoint, clock.instant(), null);

        List<Future<?>> sends = queue.drain();
        TestCaller.run(tenant, Environment.TEST, () -> webhooks.delete(endpoint));
        waitFor(sends);

        assertThat(receiver.requests(path)).hasSize(1);
        assertThat(deliveries.status(delivery)).isEqualTo("CANCELED");
        assertThat(deliveries.health(endpoint)).isEqualTo(EndpointHealth.HEALTHY);
    }

    @Test
    void deliversTheEventOfACommittedChange(
            @Autowired TenancyApi tenancy, @Autowired BillingApi billing, @Autowired JsonMapper json) throws Exception {
        TenantId organization = tenancy.createOrganization("Padaria São João", "subject-" + UUID.randomUUID())
                .test();
        String path = "/" + UUID.randomUUID();
        endpoint(organization, path, Set.of("charge.created"));

        var charge = BillingFixtures.created(TestCaller.call(
                organization,
                Environment.TEST,
                () -> billing.createCharge(ChargeRequest.of(BillingFixtures.brl(10_000)))));
        await().atMost(Duration.ofSeconds(5)).until(() -> pendingDeliveries(organization) == 1);
        drainAndWait();

        assertThat(receiver.requests(path)).singleElement().satisfies(request -> {
            JsonNode event = json.readTree(request.body());
            assertThat(event.path("type").asString()).isEqualTo("charge.created");
            assertThat(event.path("subject").asString())
                    .isEqualTo(charge.id().publicId().toString());
            assertThat(request.headers())
                    .containsEntry("webhook-id", event.path("id").asString());
        });
    }

    /**
     * The tenant's server is down for an hour after a charge is created: the attempts within it fail,
     * and the first one after it delivers the event, the same body under the same ID.
     */
    @Test
    void deliversTheEventOnceItsEndpointRecoversFromAnHourLongOutage(
            @Autowired TenancyApi tenancy, @Autowired BillingApi billing) throws Exception {
        TenantId organization = tenancy.createOrganization("Mercearia Boa Vista", "subject-" + UUID.randomUUID())
                .test();
        String path = "/" + UUID.randomUUID();
        receiver.answer(path, new TestReceiver.Answer.Status(503));
        endpoint(organization, path, Set.of("charge.created"));
        Instant outageEnds = clock.instant().plus(Duration.ofHours(1));

        TestCaller.call(
                organization,
                Environment.TEST,
                () -> billing.createCharge(ChargeRequest.of(BillingFixtures.brl(10_000))));
        await().atMost(Duration.ofSeconds(5)).until(() -> pendingDeliveries(organization) == 1);
        UUID delivery = deliveryOf(organization);
        drainAndWait();
        while (deliveries.nextAttemptAt(delivery).orElseThrow().isBefore(outageEnds)) {
            clock.advance(Duration.between(
                    clock.instant(), deliveries.nextAttemptAt(delivery).orElseThrow()));
            drainAndWait();
        }
        assertThat(receiver.requests(path)).as("attempts during the outage").hasSize(4);
        clock.advance(Duration.between(clock.instant(), outageEnds));
        receiver.answer(path, new TestReceiver.Answer.Status(204));
        clock.advance(Duration.between(
                clock.instant(), deliveries.nextAttemptAt(delivery).orElseThrow()));
        drainAndWait();

        assertThat(deliveries.status(delivery)).isEqualTo("SUCCEEDED");
        assertThat(deliveries.attemptCount(delivery)).isEqualTo(5);
        List<TestReceiver.Request> requests = receiver.requests(path);
        assertThat(requests).hasSize(5);
        assertThat(requests.stream().map(request -> request.header("webhook-id")))
                .containsOnly(requests.getFirst().header("webhook-id"));
        assertThat(requests.stream().map(TestReceiver.Request::body)).containsOnly(deliveries.body(delivery));
    }

    private UUID deliveryOf(TenantId owner) {
        return this.owner
                .sql("SELECT id FROM webhooks.deliveries WHERE tenant_id = ?")
                .param(owner.value())
                .query(UUID.class)
                .single();
    }

    private void drainAndWait() throws InterruptedException, ExecutionException {
        waitFor(queue.drain());
    }

    private static void waitFor(List<Future<?>> sends) throws InterruptedException, ExecutionException {
        for (Future<?> send : sends) {
            send.get();
        }
    }

    private long pendingDeliveries(TenantId owner) {
        return this.owner
                .sql("SELECT count(*) FROM webhooks.deliveries WHERE tenant_id = ? AND status = 'PENDING'")
                .param(owner.value())
                .query(Long.class)
                .single();
    }

    private WebhookEndpointId endpoint(String path) {
        return endpoint(tenant, path, Set.of("*"));
    }

    private WebhookEndpointId endpoint(TenantId owner, String path, Set<String> types) {
        return create(owner, path, types).endpoint().id();
    }

    private CreatedEndpoint create(TenantId owner, String path, Set<String> types) {
        var request = new EndpointRequest(receiver.url(path).toString(), Optional.empty(), types);
        var result = TestCaller.call(owner, Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint();
    }

    // The signature a receiver computes from the secret it was shown and the request it received.
    private static String signature(TestReceiver.Request request, String secret) {
        var key = SigningSecret.of(Base64.getDecoder().decode(secret.substring(SigningSecret.PREFIX.length())));
        return WebhookSignature.header(
                List.of(key),
                request.header("webhook-id"),
                Long.parseLong(request.header("webhook-timestamp")),
                request.body().getBytes(StandardCharsets.UTF_8));
    }
}
