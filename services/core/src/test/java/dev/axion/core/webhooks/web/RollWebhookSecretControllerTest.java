package dev.axion.core.webhooks.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import dev.axion.core.DatabaseOwner;
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
import dev.axion.core.webhooks.CreatedEndpoint;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhooksApi;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /v1/webhook_endpoints/{id}/secret/roll} (events and webhooks design, sections 6.2 and
 * 12.2), every response checked against the contract.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class RollWebhookSecretControllerTest {

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final DatabaseOwner owner;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;
    private CreatedEndpoint endpoint;

    @Autowired
    RollWebhookSecretControllerTest(
            MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner owner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = owner;
    }

    @BeforeEach
    void createEndpoint() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
        var request = new EndpointRequest(
                "https://orders.example.com/axion/webhooks", Optional.empty(), Set.of("charge.paid"));
        var result = TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request));
        endpoint = ((CreateEndpointResult.Created) result).endpoint();
    }

    @Test
    void rollsTheSecretAndReturnsTheNewOneOnce() throws UnsupportedEncodingException {
        Instant before = Instant.now();

        MvcTestResult result = roll("roll-1", """
                {"grace_period_seconds": 3600}
                """);

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        String body = result.getResponse().getContentAsString();
        String secret = JsonPath.read(body, "$.secret");
        assertThat(secret).matches("whsec_[0-9A-Za-z+/]{43}=").isNotEqualTo(endpoint.secret());
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_endpoint",
                  "url": "https://orders.example.com/axion/webhooks",
                  "description": null,
                  "enabled_events": ["charge.paid"],
                  "status": "enabled",
                  "disabled_reason": null,
                  "created_at": "%s",
                  "secret": "%s",
                  "previous_secret_expires_at": "%s"
                }
                """.formatted(
                        endpoint.endpoint().id().publicId(),
                        JsonPath.<String>read(body, "$.created_at"),
                        secret,
                        JsonPath.<String>read(body, "$.previous_secret_expires_at")));
        assertThat(Instant.parse(JsonPath.read(body, "$.previous_secret_expires_at")))
                .isBetween(before.plus(Duration.ofHours(1)), Instant.now().plus(Duration.ofHours(1)));
    }

    @Test
    void keepsThePreviousSecret24HoursWithNoBody() throws UnsupportedEncodingException {
        Instant before = Instant.now();

        MvcTestResult result = roll("roll-1", null);

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(Instant.parse(
                        JsonPath.read(result.getResponse().getContentAsString(), "$.previous_secret_expires_at")))
                .isBetween(before.plus(Duration.ofHours(24)), Instant.now().plus(Duration.ofHours(24)));
    }

    @Test
    void answersARepeatWithoutTheSecret() throws UnsupportedEncodingException {
        MvcTestResult first = roll("roll-1", "{}");
        String secret = JsonPath.read(first.getResponse().getContentAsString(), "$.secret");

        MvcTestResult repeat = roll("roll-1", "{}");

        assertThat(repeat).hasStatus(HttpStatus.OK).hasHeader("Idempotent-Replayed", "true");
        assertThat(repeat).bodyJson().extractingPath("$.secret").isNull();
        assertThat(repeat)
                .bodyJson()
                .extractingPath("$.previous_secret_expires_at")
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.previous_secret_expires_at"));
        assertThat(secrets()).isEqualTo(2);
        assertThat(storedResponses()).noneMatch(stored -> stored.contains(secret));
    }

    @Test
    void refusesToRollAgainDuringTheGracePeriod() {
        assertThat(roll("roll-1", "{}")).hasStatus(HttpStatus.OK);

        MvcTestResult again = roll("roll-2", "{}");

        assertThat(again)
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("grace_period_active");
        assertThat(secrets()).isEqualTo(2);
    }

    @Test
    void rollsAgainAtOnceAfterAZeroGracePeriod() {
        assertThat(roll("roll-1", """
                        {"grace_period_seconds": 0}
                        """)).hasStatus(HttpStatus.OK);

        assertThat(roll("roll-2", "{}")).hasStatus(HttpStatus.OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "604801"})
    void refusesAGracePeriodOutsideZeroToSevenDays(String seconds) {
        MvcTestResult result = roll("roll-1", """
                {"grace_period_seconds": %s}
                """.formatted(seconds));

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("grace_period_seconds"));
        assertThat(secrets()).isOne();
    }

    @Test
    void refusesAnUnknownField() {
        MvcTestResult result = roll("roll-1", """
                {"grace_period": 3600}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("grace_period"));
        assertThat(secrets()).isOne();
    }

    @ParameterizedTest
    @ValueSource(strings = {"we_00000000000000000000000000", "we_malformed", "ch_01jdr2m4n5p6q7r8s9t0v1w2x3"})
    void answersNotFoundForAnUnknownOrMalformedEndpoint(String id) {
        MvcTestResult result = send(mvc.post()
                .uri("/v1/webhook_endpoints/{id}/secret/roll", id)
                .header("Authorization", "Bearer " + key.secret())
                .header("Idempotency-Key", "roll-1"));

        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        MvcTestResult result = roll(issued(Permission.CHARGES_WRITE), "roll-1", "{}");

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(secrets()).isOne();
    }

    private MvcTestResult roll(String idempotencyKey, @Nullable String body) {
        return roll(key, idempotencyKey, body);
    }

    private MvcTestResult roll(IssuedApiKey caller, String idempotencyKey, @Nullable String body) {
        var request = mvc.post()
                .uri(
                        "/v1/webhook_endpoints/{id}/secret/roll",
                        endpoint.endpoint().id().publicId())
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", idempotencyKey);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return send(request);
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        contract.assertConforms(result);
        return result;
    }

    private IssuedApiKey issued(Permission scope) {
        return AccessFixtures.issue(
                access, AccessFixtures.admin(organization, Environment.TEST), "Integrations", Set.of(scope));
    }

    private long secrets() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.secrets WHERE endpoint_id = ?")
                .param(endpoint.endpoint().id().value())
                .query(Long.class)
                .single());
    }

    // The response bodies stored with the tenant's idempotency keys, which are never null.
    private List<String> storedResponses() {
        return owner.jdbc()
                .sql("SELECT convert_from(response_body, 'UTF8') FROM idempotency.keys WHERE tenant_id = ?")
                .param(organization.test().value())
                .query((row, number) -> Objects.requireNonNull(row.getString(1)))
                .list();
    }
}
