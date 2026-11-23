package dev.axion.core.webhooks.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.TestCaller;
import dev.axion.core.access.AccessApi;
import dev.axion.core.access.AccessFixtures;
import dev.axion.core.access.IssuedApiKey;
import dev.axion.core.access.TenantBoundAccess;
import dev.axion.core.shared.Environment;
import dev.axion.core.tenancy.NewOrganization;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.tenancy.TenancyApi;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhooksApi;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code GET /v1/webhook_endpoints} and {@code GET /v1/webhook_endpoints/{id}} (events and webhooks
 * design, section 12.2), every response checked against the contract.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class ReadWebhookEndpointsControllerTest {

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    ReadWebhookEndpointsControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
    }

    @BeforeEach
    void issueKey() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
    }

    @Test
    void readsAnEndpointWithoutItsSecret() {
        EndpointView endpoint = create(Optional.of("Order service"), Set.of("charge.refunded", "charge.paid"));

        MvcTestResult result = get("/v1/webhook_endpoints/" + endpoint.id().publicId(), key);

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_endpoint",
                  "url": "https://orders.example.com/axion/webhooks",
                  "description": "Order service",
                  "enabled_events": ["charge.paid", "charge.refunded"],
                  "status": "enabled",
                  "disabled_reason": null,
                  "created_at": "%s"
                }
                """.formatted(
                        endpoint.id().publicId(), endpoint.createdAt()));
    }

    @Test
    void readsAnEndpointWithNoDescriptionForEveryType() {
        EndpointView endpoint = create(Optional.empty(), Set.of("*"));

        MvcTestResult result = get("/v1/webhook_endpoints/" + endpoint.id().publicId(), key);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.description").isNull();
        assertThat(result).bodyJson().extractingPath("$.enabled_events").isEqualTo(List.of("*"));
    }

    @Test
    void answersNotFoundForAnUnknownOrMalformedId() {
        assertNotFound(get("/v1/webhook_endpoints/we_01jdr2m4n5p6q7r8s9t0v1w2x3", key));
        assertNotFound(get("/v1/webhook_endpoints/ch_01jdr2m4n5p6q7r8s9t0v1w2x3", key));
        assertNotFound(get("/v1/webhook_endpoints/nonsense", key));
    }

    @Test
    void listsTheEndpointsNewestFirstAPageAtATime() {
        EndpointView first = create(Optional.empty(), Set.of("charge.paid"));
        EndpointView second = create(Optional.empty(), Set.of("charge.paid"));
        EndpointView third = create(Optional.empty(), Set.of("charge.paid"));

        MvcTestResult page = get("/v1/webhook_endpoints?limit=2", key);
        MvcTestResult next = get(
                "/v1/webhook_endpoints?limit=2&starting_after=" + second.id().publicId(), key);

        assertThat(page).hasStatus(HttpStatus.OK);
        assertThat(page).bodyJson().extractingPath("$.object").isEqualTo("list");
        assertThat(page)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(
                        third.id().publicId().toString(), second.id().publicId().toString()));
        assertThat(page).bodyJson().extractingPath("$.has_more").isEqualTo(true);
        assertThat(next)
                .bodyJson()
                .extractingPath("$.data[*].id")
                .isEqualTo(List.of(first.id().publicId().toString()));
        assertThat(next).bodyJson().extractingPath("$.has_more").isEqualTo(false);
    }

    @Test
    void listsNothingForATenantWithNoEndpoints() {
        MvcTestResult result = get("/v1/webhook_endpoints", key);

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {"object": "list", "data": [], "has_more": false}
                """);
    }

    @Test
    void refusesParametersOutsideTheirRules() {
        MvcTestResult result = get("/v1/webhook_endpoints?limit=101&starting_after=we_nonsense", key);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("limit", "starting_after"));
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        IssuedApiKey reader = issued(Permission.CHARGES_READ);

        MvcTestResult result = get("/v1/webhook_endpoints", reader);

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private EndpointView create(Optional<String> description, Set<String> types) {
        var request = new EndpointRequest("https://orders.example.com/axion/webhooks", description, types);
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        return ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    private MvcTestResult get(String uri, IssuedApiKey caller) {
        MvcTestResult result = mvc.get()
                .uri(uri)
                .header("Authorization", "Bearer " + caller.secret())
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
