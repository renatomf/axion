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
 * {@code DELETE /v1/webhook_endpoints/{id}} (events and webhooks design, section 12.2), every
 * response checked against the contract.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class DeleteWebhookEndpointControllerTest {

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;
    private EndpointView endpoint;

    @Autowired
    DeleteWebhookEndpointControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks) {
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
                "https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("charge.paid"));
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        endpoint = ((CreateEndpointResult.Created) result).endpoint().endpoint();
    }

    @Test
    void deletesTheEndpoint() {
        assertThat(delete(endpoint.id().publicId().toString(), key)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.endpoint(endpoint.id())))
                .isEmpty();
    }

    @Test
    void answersNotFoundForAnEndpointAlreadyDeleted() {
        delete(endpoint.id().publicId().toString(), key);

        assertNotFound(delete(endpoint.id().publicId().toString(), key));
    }

    @Test
    void answersNotFoundForAnUnknownOrMalformedId() {
        assertNotFound(delete("we_01jdr2m4n5p6q7r8s9t0v1w2x3", key));
        assertNotFound(delete("nonsense", key));
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        MvcTestResult result = delete(endpoint.id().publicId().toString(), issued(Permission.CHARGES_READ));

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.endpoint(endpoint.id())))
                .isPresent();
    }

    private MvcTestResult delete(String id, IssuedApiKey caller) {
        MvcTestResult result = mvc.delete()
                .uri("/v1/webhook_endpoints/" + id)
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
