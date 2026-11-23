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
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhooksApi;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The webhook endpoint operations of {@code /internal} (events and webhooks design, section 12.2),
 * every response checked against the contract. Tokens skip decoding: real ones are proven in
 * {@code TenantResolutionTest}.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, KeycloakTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class TenantWebhookEndpointsControllerTest {

    private static final String PATH = "/internal/tenants/{tenant_id}/webhook_endpoints";

    private static final Duration RECENT = Duration.ofMinutes(1);

    private static final Duration OLD = Duration.ofMinutes(6);

    private final MockMvcTester mvc;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final JdbcClient owner;

    private String subject = "";
    private NewOrganization organization;

    @Autowired
    TenantWebhookEndpointsControllerTest(
            MockMvcTester mvc, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner databaseOwner) {
        this.mvc = mvc;
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = databaseOwner.jdbc();
    }

    @BeforeEach
    void createOrganization() {
        subject = UUID.randomUUID().toString();
        organization = tenancy.createOrganization("Acme", subject);
    }

    @Test
    void readsAnEndpointWithoutItsSecretWithoutAStepUp() {
        EndpointView endpoint = create(organization.test());

        MvcTestResult result = send(HttpMethod.GET, PATH + "/{endpoint_id}", OLD, organization.test(), endpoint);

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_endpoint",
                  "url": "https://orders.example.com/axion/webhooks",
                  "description": null,
                  "enabled_events": ["charge.paid"],
                  "status": "enabled",
                  "disabled_reason": null,
                  "created_at": "%s"
                }
                """.formatted(
                        endpoint.id().publicId(), endpoint.createdAt()));
    }

    @Test
    void listsTheEndpointsNewestFirstAPageAtATimeWithoutAStepUp() {
        EndpointView first = create(organization.test());
        EndpointView second = create(organization.test());

        MvcTestResult page = send(HttpMethod.GET, PATH + "?limit=1", OLD, organization.test());
        MvcTestResult next = send(
                HttpMethod.GET, PATH + "?limit=1&starting_after=" + second.id().publicId(), OLD, organization.test());

        assertThat(page).hasStatus(HttpStatus.OK);
        assertThat(page)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(second.id().publicId().toString()));
        assertThat(page).bodyJson().extractingPath("$.has_more").isEqualTo(true);
        assertThat(next)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(first.id().publicId().toString()));
        assertThat(next).bodyJson().extractingPath("$.has_more").isEqualTo(false);
    }

    @Test
    void refusesListParametersOutsideTheirRules() {
        MvcTestResult result =
                send(HttpMethod.GET, PATH + "?limit=101&starting_after=we_nonsense", RECENT, organization.test());

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("limit", "starting_after"));
    }

    @Test
    void findsNoEndpointOfTheOtherEnvironmentOrWithAMalformedId() {
        EndpointView live = create(organization.live());

        assertNotFound(send(HttpMethod.GET, PATH + "/{endpoint_id}", RECENT, organization.test(), live));
        assertNotFound(send(HttpMethod.GET, PATH + "/nonsense", RECENT, organization.test()));
    }

    @Test
    void refusesAMemberWhoCannotManageWebhooks() {
        owner.sql("UPDATE tenancy.member_roles SET role = 'READ_ONLY' WHERE member_id = ?")
                .param(organization.admin().value())
                .update();

        assertThat(send(HttpMethod.GET, PATH, RECENT, organization.test()))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private EndpointView create(TenantId tenant) {
        var request = new EndpointRequest(
                "https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("charge.paid"));
        Environment environment = tenant.equals(organization.live()) ? Environment.LIVE : Environment.TEST;
        var result = TestCaller.call(tenant, environment, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    private MvcTestResult send(
            HttpMethod method, String path, Duration sinceAuthentication, TenantId tenant, EndpointView endpoint) {
        return send(
                method,
                path,
                sinceAuthentication,
                tenant.publicId(),
                endpoint.id().publicId());
    }

    private MvcTestResult send(HttpMethod method, String path, Duration sinceAuthentication, TenantId tenant) {
        return send(method, path, sinceAuthentication, tenant.publicId());
    }

    private MvcTestResult send(HttpMethod method, String path, Duration sinceAuthentication, Object... variables) {
        MvcTestResult result = mvc.method(method)
                .uri(path, variables)
                .with(jwt().jwt(token -> token.subject(subject)
                        .claim(
                                "auth_time",
                                Instant.now().minus(sinceAuthentication).getEpochSecond())))
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
