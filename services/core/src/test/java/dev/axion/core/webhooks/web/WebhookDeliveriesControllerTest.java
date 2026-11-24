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
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDeliveries;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookAttemptId;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.EventTypes;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code GET /v1/webhook_endpoints/{id}/deliveries} and {@code GET …/deliveries/{delivery_id}}
 * (events and webhooks design, sections 8.7 and 12.2), every response checked against the
 * contract. The owner arranges the deliveries, each with a UUIDv7 a millisecond after the previous.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class WebhookDeliveriesControllerTest {

    private static final Instant STARTED = Instant.parse("2026-11-24T12:00:00Z");

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final TestDeliveries deliveries;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;
    private WebhookEndpointId endpoint;
    private int made;

    @Autowired
    WebhookDeliveriesControllerTest(
            MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner owner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = owner.jdbc();
        this.deliveries = new TestDeliveries(this.owner);
    }

    @BeforeEach
    void issueKeyAndCreateEndpoint() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
        endpoint = create();
    }

    @Test
    void readsADeliveryWithItsAttemptsOldestFirst() {
        UUID delivery = delivered(endpoint, "charge.paid", "SUCCEEDED");
        deliveries.attempt(delivery, organization.test(), STARTED.plusSeconds(60), 87, 200, null, "<b>ok</b>");
        deliveries.attempt(delivery, organization.test(), STARTED, 10_000, null, "TIMEOUT", null);
        List<String> attempts = owner.sql("SELECT id FROM webhooks.attempts WHERE delivery_id = ? ORDER BY started_at")
                .param(delivery)
                .query((row, number) -> new WebhookAttemptId(row.getObject("id", UUID.class))
                        .publicId()
                        .toString())
                .list();

        MvcTestResult result = get(path(endpoint) + "/" + publicId(delivery));

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_delivery",
                  "endpoint": "%s",
                  "event": "%s",
                  "event_type": "charge.paid",
                  "status": "succeeded",
                  "attempt_count": 1,
                  "next_attempt_at": null,
                  "created_at": "%s",
                  "attempts": [
                    {
                      "id": "%s",
                      "object": "webhook_attempt",
                      "replay": false,
                      "started_at": "2026-11-24T12:00:00Z",
                      "duration_ms": 10000,
                      "status_code": null,
                      "failure": "timeout",
                      "response_excerpt": null
                    },
                    {
                      "id": "%s",
                      "object": "webhook_attempt",
                      "replay": false,
                      "started_at": "2026-11-24T12:01:00Z",
                      "duration_ms": 87,
                      "status_code": 200,
                      "failure": null,
                      "response_excerpt": "<b>ok</b>"
                    }
                  ]
                }
                """.formatted(
                        publicId(delivery),
                        endpoint.publicId(),
                        new EventId(eventOf(delivery)).publicId(),
                        createdAt(delivery),
                        attempts.get(0),
                        attempts.get(1)));
    }

    @Test
    void readsAPendingDeliveryWithItsNextAttempt() {
        UUID delivery = delivered(endpoint, "charge.created", "PENDING");

        MvcTestResult result = get(path(endpoint) + "/" + publicId(delivery));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("pending");
        assertThat(result).bodyJson().extractingPath("$.next_attempt_at").isEqualTo("2999-01-01T00:00:00Z");
        assertThat(result).bodyJson().extractingPath("$.attempts").isEqualTo(List.of());
    }

    @Test
    void listsTheDeliveriesNewestFirstAPageAtATimeWithoutTheirAttempts() {
        UUID first = delivered(endpoint, "charge.paid", "SUCCEEDED");
        UUID second = delivered(endpoint, "charge.paid", "DEAD");
        UUID third = delivered(endpoint, "charge.paid", "SUCCEEDED");
        deliveries.attempt(third, organization.test(), STARTED, 5, 204, null, null);

        MvcTestResult page = get(path(endpoint) + "?limit=2");
        MvcTestResult next = get(path(endpoint) + "?limit=2&starting_after=" + publicId(second));

        assertThat(page).hasStatus(HttpStatus.OK);
        assertThat(page).bodyJson().extractingPath("$.object").isEqualTo("list");
        assertThat(page)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(publicId(third), publicId(second)));
        assertThat(page).bodyJson().doesNotHavePath("$.data[0].attempts");
        assertThat(page).bodyJson().extractingPath("$.has_more").isEqualTo(true);
        assertThat(next).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(publicId(first)));
        assertThat(next).bodyJson().extractingPath("$.has_more").isEqualTo(false);
    }

    @Test
    void filtersTheDeliveriesByStatusAndType() {
        UUID createdAndDead = delivered(endpoint, "charge.created", "DEAD");
        UUID paidAndDead = delivered(endpoint, "charge.paid", "DEAD");
        delivered(endpoint, "charge.paid", "SUCCEEDED");

        assertThat(get(path(endpoint) + "?status=dead&type=charge.paid"))
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(publicId(paidAndDead)));
        assertThat(get(path(endpoint) + "?status=dead"))
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(publicId(paidAndDead), publicId(createdAndDead)));
    }

    @Test
    void listsNothingForAnEndpointWithNoDeliveries() {
        MvcTestResult result = get(path(endpoint));

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"object": "list", "data": [], "has_more": false}
                """);
    }

    @Test
    void answersNotFoundForAnUnknownEndpointOrDelivery() {
        UUID delivery = delivered(endpoint, "charge.paid", "SUCCEEDED");
        WebhookEndpointId other = create();

        assertNotFound(get(path(new WebhookEndpointId(UUID.randomUUID()))));
        assertNotFound(get("/v1/webhook_endpoints/nonsense/deliveries"));
        assertNotFound(get(path(endpoint) + "/whd_01jdr3b7a8b9c0d1e2f3g4h5j6"));
        assertNotFound(get(path(endpoint) + "/nonsense"));
        assertNotFound(get(path(other) + "/" + publicId(delivery)));
    }

    @Test
    void refusesParametersOutsideTheirRules() {
        MvcTestResult result = get(path(endpoint) + "?limit=0&starting_after=whd_nonsense&status=lost&type=*");

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("limit", "starting_after", "status", "type"));
    }

    @Test
    void filtersByExactlyTheTypesEndpointsReceive() {
        Pattern pattern = Pattern.compile(DeliveryQuery.EVENT_TYPE);

        assertThat(EventTypes.WEBHOOK).allMatch(type -> pattern.matcher(type).matches());
        assertThat(DeliveryQuery.EVENT_TYPE.split("\\|")).hasSameSizeAs(EventTypes.WEBHOOK);
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        IssuedApiKey reader = issued(Permission.CHARGES_READ);

        MvcTestResult result = mvc.get()
                .uri(path(endpoint))
                .header("Authorization", "Bearer " + reader.secret())
                .exchange();
        contract.assertConforms(result);

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private WebhookEndpointId create() {
        var request = new EndpointRequest("https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("*"));
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    // A UUIDv7 a millisecond after the previous one, so the deliveries list in the order they were made.
    private UUID delivered(WebhookEndpointId to, String type, String status) {
        Clock at = Clock.fixed(STARTED.plusMillis(made++), ZoneOffset.UTC);
        return deliveries.logged(new UuidV7Generator(at).next(), organization.test(), to, type, status);
    }

    private UUID eventOf(UUID delivery) {
        return owner.sql("SELECT event_id FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(UUID.class)
                .single();
    }

    private Instant createdAt(UUID delivery) {
        return owner.sql("SELECT created_at FROM webhooks.deliveries WHERE id = ?")
                .param(delivery)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();
    }

    private static String path(WebhookEndpointId to) {
        return "/v1/webhook_endpoints/" + to.publicId() + "/deliveries";
    }

    private static String publicId(UUID delivery) {
        return new WebhookDeliveryId(delivery).publicId().toString();
    }

    private MvcTestResult get(String uri) {
        MvcTestResult result = mvc.get()
                .uri(uri)
                .header("Authorization", "Bearer " + key.secret())
                .exchange();
        contract.assertConforms(result);
        return result;
    }

    private IssuedApiKey issued(Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Integrations", Set.of(scope));
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }
}
