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
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.TestDns;
import dev.axion.core.webhooks.WebhooksApi;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code POST /v1/webhook_endpoints} (events and webhooks design, sections 6 and 7.1), every
 * response checked against the contract.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, TestDns.class})
@AutoConfigureMockMvc
class WebhookEndpointsControllerTest {

    private static final String BODY = """
            {
              "url": "https://orders.example.com/axion/webhooks",
              "description": "Order service",
              "enabled_events": ["charge.refunded", "charge.paid"]
            }
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final WebhooksApi webhooks;
    private final DatabaseOwner owner;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    WebhookEndpointsControllerTest(
            MockMvcTester mvc, AccessApi access, TenancyApi tenancy, WebhooksApi webhooks, DatabaseOwner owner) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.webhooks = webhooks;
        this.owner = owner;
    }

    @BeforeEach
    void issueKey() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Permission.WEBHOOKS_MANAGE);
    }

    @Test
    void registersAnEndpointAndReturnsItsSecretOnce() throws UnsupportedEncodingException {
        MvcTestResult result = create("endpoint-1", BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        String body = result.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        String secret = JsonPath.read(body, "$.secret");
        assertThat(secret).matches("whsec_[0-9A-Za-z+/]{43}=");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/webhook_endpoints/" + id);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "webhook_endpoint",
                  "url": "https://orders.example.com/axion/webhooks",
                  "description": "Order service",
                  "enabled_events": ["charge.paid", "charge.refunded"],
                  "status": "enabled",
                  "disabled_reason": null,
                  "created_at": "%s",
                  "secret": "%s"
                }
                """.formatted(
                        id, JsonPath.<String>read(body, "$.created_at"), secret));

        MvcTestResult read = send(mvc.get()
                .uri(Objects.requireNonNull(result.getResponse().getHeader("Location")))
                .header("Authorization", "Bearer " + key.secret()));
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(read.getResponse().getContentAsString()).doesNotContain("secret");
    }

    @Test
    void answersARepeatWithTheSameEndpointAndNoSecret() throws UnsupportedEncodingException {
        MvcTestResult first = create("endpoint-1", BODY);
        String secret = JsonPath.read(first.getResponse().getContentAsString(), "$.secret");

        MvcTestResult repeat = create("endpoint-1", BODY);

        assertThat(repeat)
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Idempotent-Replayed", "true")
                .hasHeader(
                        "Location", Objects.requireNonNull(first.getResponse().getHeader("Location")));
        assertThat(repeat)
                .bodyJson()
                .extractingPath("$.id")
                .isEqualTo(JsonPath.read(first.getResponse().getContentAsString(), "$.id"));
        assertThat(repeat).bodyJson().extractingPath("$.secret").isNull();
        assertThat(endpoints()).isOne();
        assertThat(storedResponses()).noneMatch(stored -> stored.contains(secret));
    }

    @ParameterizedTest
    @CsvSource({
        "http://orders.example.com/axion, must_be_https",
        "https://user@orders.example.com/axion, malformed",
        "https://93.184.215.14/axion, must_use_domain_name",
        "https://orders.example.com:22/axion, port_not_allowed",
        "https://localhost/axion, must_be_public",
        "https://hooks." + TestDns.PRIVATE + "/axion, must_be_public",
        "https://hooks." + TestDns.UNRESOLVABLE + "/axion, must_resolve"
    })
    void refusesAUrlNamingTheRuleItFailed(String url, String reason) {
        MvcTestResult result = create("endpoint-1", """
                {"url": "%s", "enabled_events": ["charge.paid"]}
                """.formatted(url));

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("validation_failed");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("url");
        assertThat(result).bodyJson().extractingPath("$.errors[0].reason").isEqualTo(reason);
        assertThat(endpoints()).isZero();
    }

    @Test
    void neverNamesTheAddressesAPrivateNameResolvedTo() throws UnsupportedEncodingException {
        MvcTestResult result = create("endpoint-1", """
                {"url": "https://hooks.%s/axion", "enabled_events": ["charge.paid"]}
                """.formatted(TestDns.PRIVATE));

        assertThat(result.getResponse().getContentAsString()).doesNotContain("10.0.0.7");
    }

    @Test
    void refusesFieldsOutsideTheirRules() {
        MvcTestResult result = create("endpoint-1", """
                {
                  "url": "https://orders.example.com/axion",
                  "description": "%s",
                  "enabled_events": ["charge.paid", "charge.unknown", "webhook_endpoint.disabled"]
                }
                """.formatted("d".repeat(141)));

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("description", "enabled_events[1]", "enabled_events[2]"));
        assertThat(endpoints()).isZero();
    }

    @Test
    void refusesTheWildcardBesideATypeAndNoTypeAtAll() {
        MvcTestResult beside = create("endpoint-1", """
                {"url": "https://orders.example.com/axion", "enabled_events": ["*", "charge.paid"]}
                """);
        MvcTestResult none = create("endpoint-2", """
                {"url": "https://orders.example.com/axion", "enabled_events": []}
                """);

        assertThat(beside)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("enabled_events"));
        assertThat(none)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("enabled_events"));
    }

    @Test
    void registersAnEndpointForEveryType() {
        MvcTestResult result = create("endpoint-1", """
                {"url": "https://orders.example.com/axion", "enabled_events": ["*"]}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.enabled_events").isEqualTo(List.of("*"));
        assertThat(result).bodyJson().extractingPath("$.description").isNull();
    }

    @Test
    void refusesABodyThatNamesATenant() {
        MvcTestResult result = create("endpoint-1", """
                {"url": "https://orders.example.com/axion", "enabled_events": ["charge.paid"], "tenant_id": "x"}
                """);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("tenant_id"));
    }

    @Test
    void refusesTheSeventeenthEndpoint() {
        var request = new EndpointRequest("https://orders.example.com/axion", Optional.empty(), Set.of("charge.paid"));
        for (int i = 0; i < WebhooksApi.MAX_ENDPOINTS; i++) {
            assertThat(TestCaller.call(organization.test(), Environment.TEST, () -> webhooks.create(request)))
                    .isInstanceOf(CreateEndpointResult.Created.class);
        }

        MvcTestResult result = create("endpoint-17", BODY);

        assertThat(result)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("limit_exceeded");
        assertThat(endpoints()).isEqualTo(WebhooksApi.MAX_ENDPOINTS);
    }

    @Test
    void refusesAKeyWithoutWebhooksManage() {
        MvcTestResult result = create(issued(Permission.CHARGES_WRITE), "endpoint-1", BODY);

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(endpoints()).isZero();
    }

    private MvcTestResult create(String idempotencyKey, String body) {
        return create(key, idempotencyKey, body);
    }

    private MvcTestResult create(IssuedApiKey caller, String idempotencyKey, String body) {
        return send(mvc.post()
                .uri("/v1/webhook_endpoints")
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    private long endpoints() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM webhooks.endpoints WHERE tenant_id = ?")
                .param(organization.test().value())
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
