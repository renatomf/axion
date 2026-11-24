package dev.axion.core.webhooks.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointChanges;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.TestDeliveries;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay} (events and webhooks
 * design, sections 8.7 and 12.2), every response checked against the contract. Nothing is sent
 * here; the queue's tests send what is queued.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class ReplayWebhookDeliveryControllerTest {

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final TestDeliveries deliveries;
    private final UuidV7Generator ids = new UuidV7Generator(Clock.systemUTC());
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;
    private WebhookEndpointId endpoint;
    private UUID delivery;

    @Autowired
    ReplayWebhookDeliveryControllerTest(
            MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner owner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.deliveries = new TestDeliveries(this.owner);
    }

    @BeforeEach
    void createEndpointWithADeadDelivery() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
        endpoint = create();
        delivery = deliveries.logged(ids.next(), organization.test(), endpoint, "charge.paid", "DEAD");
    }

    @Test
    void queuesAReplayAndAnswersAcceptedWithTheDeliveryAsItIs() {
        MvcTestResult result = replay(endpoint, delivery, "replay-1");

        assertThat(result).hasStatus(HttpStatus.ACCEPTED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_delivery",
                  "endpoint": "%s",
                  "event": "%s",
                  "event_type": "charge.paid",
                  "status": "dead",
                  "attempt_count": 1,
                  "next_attempt_at": null,
                  "created_at": "%s"
                }
                """.formatted(
                publicId(delivery), endpoint.publicId(), new EventId(event(delivery)).publicId(), createdAt(delivery)));
        assertThat(deliveries.replays(delivery)).containsExactly("PENDING");
    }

    @Test
    void answersARepeatWithTheFirstAnswerAndQueuesNothingMore() {
        assertThat(replay(endpoint, delivery, "replay-1")).hasStatus(HttpStatus.ACCEPTED);

        MvcTestResult repeat = replay(endpoint, delivery, "replay-1");

        assertThat(repeat).hasStatus(HttpStatus.ACCEPTED).hasHeader("Idempotent-Replayed", "true");
        assertThat(deliveries.replays(delivery)).containsExactly("PENDING");
    }

    @Test
    void answersConflictWhileTheEndpointIsDisabled() {
        var changes = new EndpointChanges(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(EndpointStatus.DISABLED));
        TestCaller.run(organization.test(), Environment.TEST, () -> webhooks.update(endpoint, changes));

        MvcTestResult result = replay(endpoint, delivery, "replay-1");

        assertThat(result)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("endpoint_disabled");
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void answersConflictOnceTheDeliveryWasReplayedTenTimesADay() {
        for (int i = 0; i < WebhooksApi.MAX_REPLAYS; i++) {
            assertThat(replay(endpoint, delivery, "replay-" + i)).hasStatus(HttpStatus.ACCEPTED);
        }

        MvcTestResult result = replay(endpoint, delivery, "replay-" + WebhooksApi.MAX_REPLAYS);

        assertThat(result)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("replay_limit_reached");
        assertThat(deliveries.replays(delivery)).hasSize(WebhooksApi.MAX_REPLAYS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"whd_00000000000000000000000000", "whd_malformed", "evt_01jdr2m4n5p6q7r8s9t0v1w2x3"})
    void answersNotFoundForAnUnknownOrMalformedDelivery(String id) {
        MvcTestResult result = send(mvc.post()
                .uri("/v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay", endpoint.publicId(), id)
                .header("Authorization", "Bearer " + key.secret())
                .header("Idempotency-Key", "replay-1"));

        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }

    @Test
    void answersNotFoundForADeliveryOfAnotherEndpoint() {
        MvcTestResult result = replay(create(), delivery, "replay-1");

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        MvcTestResult result = send(mvc.post()
                .uri(
                        "/v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay",
                        endpoint.publicId(),
                        publicId(delivery))
                .header(
                        "Authorization",
                        "Bearer " + issued(Permission.CHARGES_WRITE).secret())
                .header("Idempotency-Key", "replay-1"));

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    private MvcTestResult replay(WebhookEndpointId to, UUID replayed, String idempotencyKey) {
        return send(mvc.post()
                .uri("/v1/webhook_endpoints/{id}/deliveries/{delivery_id}/replay", to.publicId(), publicId(replayed))
                .header("Authorization", "Bearer " + key.secret())
                .header("Idempotency-Key", idempotencyKey));
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        contract.assertConforms(result);
        return result;
    }

    private WebhookEndpointId create() {
        var request = new EndpointRequest("https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("*"));
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    private UUID event(UUID of) {
        return owner.sql("SELECT event_id FROM webhooks.deliveries WHERE id = ?")
                .param(of)
                .query((row, number) -> row.getObject("event_id", UUID.class))
                .single();
    }

    private String createdAt(UUID of) {
        return owner.sql("SELECT created_at FROM webhooks.deliveries WHERE id = ?")
                .param(of)
                .query((row, number) -> row.getObject("created_at", OffsetDateTime.class)
                        .toInstant()
                        .toString())
                .single();
    }

    private IssuedApiKey issued(Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Integrations", Set.of(scope));
    }

    private static String publicId(UUID delivery) {
        return new WebhookDeliveryId(delivery).publicId().toString();
    }
}
