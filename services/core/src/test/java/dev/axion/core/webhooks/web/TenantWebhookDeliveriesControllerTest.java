package dev.axion.core.webhooks.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.KeycloakTestConfiguration;
import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.TenantId;
import dev.axion.core.shared.UuidV7Generator;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDeliveries;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhookDeliveryId;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * The delivery log on {@code /internal} (events and webhooks design, section 12.2), every response
 * checked against the contract. Tokens skip decoding: real ones are proven in {@code
 * TenantResolutionTest}. Reading needs no recent authentication.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class TenantWebhookDeliveriesControllerTest {

    private static final String PATH = "/internal/tenants/{tenant_id}/webhook_endpoints/{endpoint_id}/deliveries";

    private static final Duration OLD = Duration.ofMinutes(6);

    private static final Instant STARTED = Instant.parse("2026-11-24T12:00:00Z");

    private final MockMvcTester mvc;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final JdbcClient owner;
    private final TestDeliveries deliveries;

    private String subject = "";
    private NewOrganization organization;
    private WebhookEndpointId endpoint;
    private int made;

    @Autowired
    TenantWebhookDeliveriesControllerTest(
            MockMvcTester mvc, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner databaseOwner) {
        this.mvc = mvc;
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = databaseOwner.jdbc();
        this.deliveries = new TestDeliveries(owner);
    }

    @BeforeEach
    void createOrganizationAndEndpoint() {
        subject = UUID.randomUUID().toString();
        organization = tenancy.createOrganization("Acme", subject);
        endpoint = create(organization.test());
    }

    @Test
    void listsTheDeliveriesNewestFirstAPageAtATime() {
        UUID first = delivered(organization.test(), endpoint, "charge.paid", "SUCCEEDED");
        UUID second = delivered(organization.test(), endpoint, "charge.paid", "DEAD");

        MvcTestResult page = get(PATH + "?limit=1", organization.test(), endpoint);
        MvcTestResult next = get(PATH + "?limit=1&starting_after=" + publicId(second), organization.test(), endpoint);

        assertThat(page).hasStatus(HttpStatus.OK);
        assertThat(page).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(publicId(second)));
        assertThat(page).bodyJson().extractingPath("$.has_more").isEqualTo(true);
        assertThat(next).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(publicId(first)));
        assertThat(next).bodyJson().extractingPath("$.has_more").isEqualTo(false);
    }

    @Test
    void filtersTheDeliveriesByStatusAndType() {
        delivered(organization.test(), endpoint, "charge.created", "DEAD");
        UUID paidAndDead = delivered(organization.test(), endpoint, "charge.paid", "DEAD");

        MvcTestResult result = get(PATH + "?status=dead&type=charge.paid", organization.test(), endpoint);

        assertThat(result).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(publicId(paidAndDead)));
    }

    @Test
    void readsADeliveryWithItsAttempts() {
        UUID delivery = delivered(organization.test(), endpoint, "charge.paid", "SUCCEEDED");
        deliveries.attempt(delivery, organization.test(), STARTED, 87, 204, null, null);

        MvcTestResult result = get(PATH + "/{delivery_id}", organization.test(), endpoint, publicId(delivery));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(publicId(delivery));
        assertThat(result)
                .bodyJson()
                .extractingPath("$.attempts[*].status_code")
                .isEqualTo(List.of(204));
    }

    @Test
    void replaysADeliveryWithNoRecentAuthentication() {
        UUID delivery = delivered(organization.test(), endpoint, "charge.paid", "DEAD");

        MvcTestResult result = post(PATH + "/{delivery_id}/replay", organization.test(), endpoint, publicId(delivery));

        assertThat(result).hasStatus(HttpStatus.ACCEPTED);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("dead");
        assertThat(deliveries.replays(delivery)).containsExactly("PENDING");
    }

    @Test
    void answersConflictToAReplayOnceTheLimitIsReached() {
        UUID delivery = delivered(organization.test(), endpoint, "charge.paid", "DEAD");
        for (int i = 0; i < WebhooksApi.MAX_REPLAYS; i++) {
            assertThat(post(PATH + "/{delivery_id}/replay", organization.test(), endpoint, publicId(delivery)))
                    .hasStatus(HttpStatus.ACCEPTED);
        }

        assertThat(post(PATH + "/{delivery_id}/replay", organization.test(), endpoint, publicId(delivery)))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("replay_limit_reached");
    }

    @Test
    void replaysNoDeliveryOfTheOtherEnvironment() {
        WebhookEndpointId live = create(organization.live());
        UUID delivery = delivered(organization.live(), live, "charge.paid", "DEAD");

        assertNotFound(post(PATH + "/{delivery_id}/replay", organization.test(), live, publicId(delivery)));
        assertNotFound(post(PATH + "/{delivery_id}/replay", organization.test(), endpoint, publicId(delivery)));
        assertThat(deliveries.replays(delivery)).isEmpty();
    }

    @Test
    void findsNoLogOfTheOtherEnvironmentOrWithAMalformedId() {
        WebhookEndpointId live = create(organization.live());
        UUID delivery = delivered(organization.live(), live, "charge.paid", "SUCCEEDED");

        assertNotFound(get(PATH, organization.test(), live));
        assertNotFound(get(PATH + "/{delivery_id}", organization.test(), live, publicId(delivery)));
        assertNotFound(get(PATH + "/{delivery_id}", organization.test(), endpoint, publicId(delivery)));
        assertNotFound(get(PATH + "/nonsense", organization.test(), endpoint));
    }

    @Test
    void refusesListParametersOutsideTheirRules() {
        MvcTestResult result = get(PATH + "?limit=101&status=lost", organization.test(), endpoint);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("limit", "status"));
    }

    @Test
    void refusesAMemberWhoCannotManageWebhooks() {
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(get(PATH, organization.test(), endpoint))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private WebhookEndpointId create(TenantId tenant) {
        var request = new EndpointRequest("https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("*"));
        Environment environment = tenant.equals(organization.live()) ? Environment.LIVE : Environment.TEST;
        var result = TestCaller.call(tenant, environment, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint().id();
    }

    // A UUIDv7 a millisecond after the previous one, so the deliveries list in the order they were made.
    private UUID delivered(TenantId tenant, WebhookEndpointId to, String type, String status) {
        Clock at = Clock.fixed(STARTED.plusMillis(made++), ZoneOffset.UTC);
        return deliveries.logged(new UuidV7Generator(at).next(), tenant, to, type, status);
    }

    private static String publicId(UUID delivery) {
        return new WebhookDeliveryId(delivery).publicId().toString();
    }

    private MvcTestResult get(String path, TenantId tenant, WebhookEndpointId to, Object... more) {
        return send(mvc.get(), path, tenant, to, more);
    }

    private MvcTestResult post(String path, TenantId tenant, WebhookEndpointId to, Object... more) {
        return send(mvc.post(), path, tenant, to, more);
    }

    private MvcTestResult send(
            MockMvcTester.MockMvcRequestBuilder request,
            String path,
            TenantId tenant,
            WebhookEndpointId to,
            Object... more) {
        Object[] variables = new Object[2 + more.length];
        variables[0] = tenant.publicId();
        variables[1] = to.publicId();
        System.arraycopy(more, 0, variables, 2, more.length);
        MvcTestResult result = request.uri(path, variables)
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim("auth_time", Instant.now().minus(OLD).getEpochSecond())))
                .exchange();
        OpenApiContract.internal().assertConforms(result);
        return result;
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }
}
