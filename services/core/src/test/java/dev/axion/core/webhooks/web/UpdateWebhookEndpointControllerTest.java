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
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code PATCH /v1/webhook_endpoints/{id}} (events and webhooks design, section 12.2), every response
 * checked against the contract.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class UpdateWebhookEndpointControllerTest {

    private static final MediaType MERGE_PATCH = MediaType.parseMediaType("application/merge-patch+json");

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;
    private EndpointView endpoint;

    @Autowired
    UpdateWebhookEndpointControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
    }

    @BeforeEach
    void createEndpoint() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
        var request = new EndpointRequest(
                "https://orders.example.com/axion/webhooks", Optional.of("Order service"), Set.of("charge.paid"));
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        endpoint = ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    @Test
    void changesTheFieldsThePatchHas() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"url": "https://billing.example.com/hooks", "enabled_events": ["charge.refunded", "charge.paid"]}
                """);

        assertThat(result).hasStatus(HttpStatus.OK).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_endpoint",
                  "url": "https://billing.example.com/hooks",
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
    void readsAPatchSentAsJson() {
        MvcTestResult result = patch(MediaType.APPLICATION_JSON, """
                {"description": "Billing"}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.description")
                .isEqualTo("Billing");
    }

    @Test
    void removesTheDescriptionForNull() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"description": null}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.description")
                .isNull();
    }

    @Test
    void disablesTheEndpointByTheTenant() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"status": "disabled"}
                """);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo("disabled");
        assertThat(result).bodyJson().extractingPath("$.disabled_reason").isEqualTo("by_tenant");
    }

    @Test
    void changesNothingForAnEmptyPatch() {
        MvcTestResult result = patch(MERGE_PATCH, "{}");

        assertThat(result)
                .hasStatus(HttpStatus.OK)
                .bodyJson()
                .extractingPath("$.url")
                .isEqualTo(endpoint.url());
    }

    @Test
    void refusesNullForAFieldThatCannotBeRemoved() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"enabled_events": null}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors")
                .isEqualTo(List.of(Map.of("field", "enabled_events", "detail", "must not be null")));
    }

    @Test
    void refusesFieldsOutsideTheirRules() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"description": "", "enabled_events": ["*", "charge.paid"], "status": "paused"}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("description", "enabled_events", "status"));
    }

    @Test
    void refusesAFieldThePatchDoesNotDefine() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"secret": "whsec_chosen"}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("secret"));
    }

    @Test
    void refusesAUrlToAPrivateAddressNamingTheRule() {
        MvcTestResult result = patch(MERGE_PATCH, """
                {"url": "https://hooks.%s/axion"}
                """.formatted(TestDns.PRIVATE));

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors")
                .isEqualTo(List.of(Map.of(
                        "field",
                        "url",
                        "detail",
                        "must name a host on the public internet",
                        "reason",
                        "must_be_public")));
        assertThat(result).bodyText().doesNotContain("10.0.0.7");
    }

    @Test
    void answersNotFoundForAnUnknownOrMalformedId() {
        assertNotFound(patch("we_01jdr2m4n5p6q7r8s9t0v1w2x3", MERGE_PATCH, "{}", key));
        assertNotFound(patch("nonsense", MERGE_PATCH, "{}", key));
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        MvcTestResult result =
                patch(endpoint.id().publicId().toString(), MERGE_PATCH, "{}", issued(Permission.CHARGES_READ));

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    private MvcTestResult patch(MediaType type, String body) {
        return patch(endpoint.id().publicId().toString(), type, body, key);
    }

    private MvcTestResult patch(String id, MediaType type, String body, IssuedApiKey caller) {
        MvcTestResult result = mvc.patch()
                .uri("/v1/webhook_endpoints/" + id)
                .header("Authorization", "Bearer " + caller.secret())
                .contentType(type)
                .content(body)
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
