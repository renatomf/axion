package dev.finaxis.core.billing.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code POST /v1/charges} (billing design, sections 4.1 and 4.2), every response checked against the contract. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class ChargesControllerTest {

    private static final String BODY = """
            {
              "amount": 10000,
              "currency": "BRL",
              "description": "Mensalidade de novembro",
              "expires_in": 1800,
              "metadata": {"order_id": "ord_8842", "customer_ref": "c-17"}
            }
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final DatabaseOwner owner;
    private final JsonMapper json;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey key;

    @Autowired
    ChargesControllerTest(
            MockMvcTester mvc, AccessApi access, TenancyApi tenancy, DatabaseOwner owner, JsonMapper json) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner;
        this.json = json;
    }

    @BeforeEach
    void issueKey() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        key = issued(Environment.TEST, Permission.CHARGES_WRITE);
    }

    @Test
    void createsAnActiveChargeWithItsBrCode() {
        MvcTestResult result = create(BODY);

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        JsonNode charge = body(result);
        String id = charge.path("id").asString();
        String transactionId = charge.path("pix").path("transaction_id").asString();
        assertThat(id).matches("ch_[0-7][0-9a-hjkmnp-tv-z]{25}");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/charges/" + id);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {
                  "id": "%s",
                  "object": "charge",
                  "status": "active",
                  "amount": 10000,
                  "currency": "BRL",
                  "amount_refunded": 0,
                  "description": "Mensalidade de novembro",
                  "metadata": {"customer_ref": "c-17", "order_id": "ord_8842"},
                  "pix": {"transaction_id": "%s", "br_code": "%s"},
                  "expires_at": "%s",
                  "paid_at": null,
                  "created_at": "%s",
                  "livemode": false
                }
                """.formatted(
                        id,
                        transactionId,
                        charge.path("pix").path("br_code").asString(),
                        charge.path("expires_at").asString(),
                        charge.path("created_at").asString()));
        assertThat(charge.path("pix").path("br_code").asString())
                .contains("5406100.00", "5909Acme Ltda", "0525" + transactionId);
        assertThat(lifetime(charge)).isEqualTo(Duration.ofMinutes(30));
        assertThat(charges()).isOne();
    }

    @Test
    void defaultsTheExpiryToOneDayWithNoDescriptionOrMetadata() {
        MvcTestResult result = create("""
                {"amount": 100, "currency": "BRL"}
                """);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode charge = body(result);
        assertThat(charge.path("description").isNull()).isTrue();
        assertThat(charge.path("metadata").isEmpty()).isTrue();
        assertThat(lifetime(charge)).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void createsALiveChargeWithALiveKey() {
        MvcTestResult result = create(issued(Environment.LIVE, Permission.CHARGES_WRITE), "order-1", BODY);

        assertThat(result)
                .hasStatus(HttpStatus.CREATED)
                .bodyJson()
                .extractingPath("$.livemode")
                .isEqualTo(true);
    }

    @Test
    void acceptsEveryFieldAtItsLimits() {
        String metadata = IntStream.range(0, 20)
                .mapToObj(i -> "\"%s\": \"%s\"".formatted(("k" + i).repeat(20).substring(0, 40), "v".repeat(500)))
                .collect(Collectors.joining(", "));
        for (String body : List.of("""
                {"amount": 1, "currency": "BRL", "expires_in": 60}
                """, """
                {"amount": 100000000, "currency": "BRL", "expires_in": 604800, "description": "%s", "metadata": {%s}}
                """.formatted("😀".repeat(140), metadata))) {
            assertThat(create(body)).as(body).hasStatus(HttpStatus.CREATED);
        }
    }

    @Test
    void refusesAFieldOutsideItsRuleNamingIt() {
        Map<String, String> invalid = Map.ofEntries(
                Map.entry("{\"currency\": \"BRL\"}", "amount"),
                Map.entry("{\"amount\": 0, \"currency\": \"BRL\"}", "amount"),
                Map.entry("{\"amount\": 100000001, \"currency\": \"BRL\"}", "amount"),
                Map.entry("{\"amount\": 100}", "currency"),
                Map.entry("{\"amount\": 100, \"currency\": \"USD\"}", "currency"),
                Map.entry("{\"amount\": 100, \"currency\": \"BRL\", \"description\": \"\"}", "description"),
                Map.entry(
                        "{\"amount\": 100, \"currency\": \"BRL\", \"description\": \"%s\"}".formatted("a".repeat(141)),
                        "description"),
                Map.entry("{\"amount\": 100, \"currency\": \"BRL\", \"expires_in\": 59}", "expires_in"),
                Map.entry("{\"amount\": 100, \"currency\": \"BRL\", \"expires_in\": 604801}", "expires_in"),
                Map.entry(
                        "{\"amount\": 100, \"currency\": \"BRL\", \"metadata\": {%s}}"
                                .formatted(IntStream.range(0, 21)
                                        .mapToObj(i -> "\"k" + i + "\": \"v\"")
                                        .collect(Collectors.joining(", "))),
                        "metadata"),
                Map.entry(
                        "{\"amount\": 100, \"currency\": \"BRL\", \"metadata\": {\"order\": \"%s\"}}"
                                .formatted("v".repeat(501)),
                        "metadata[order]"),
                Map.entry(
                        "{\"amount\": 100, \"currency\": \"BRL\", \"metadata\": {\"order\": null}}", "metadata[order]"),
                Map.entry("{\"amount\": 100, \"currency\": \"BRL\", \"expires_at\": 1800}", "expires_at"));

        invalid.forEach((body, field) -> assertThat(create(body))
                .as(body)
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .satisfies(problem -> {
                    assertThat(problem).extractingPath("$.code").isEqualTo("validation_failed");
                    assertThat(problem).extractingPath("$.errors[*].field").isEqualTo(List.of(field));
                }));
        assertThat(charges()).isZero();
    }

    @Test
    void refusesAMetadataKeyOutsideItsRule() {
        for (String metadataKey : List.of("", "k".repeat(41))) {
            assertThat(create("""
                            {"amount": 100, "currency": "BRL", "metadata": {"%s": "v"}}
                            """.formatted(metadataKey)))
                    .as(metadataKey)
                    .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("validation_failed");
        }
        assertThat(charges()).isZero();
    }

    @Test
    void refusesAValueOfAnotherType() {
        for (String body : List.of(
                "{\"amount\": 100.5, \"currency\": \"BRL\"}",
                "{\"amount\": 100.0, \"currency\": \"BRL\"}",
                "{\"amount\": \"100\", \"currency\": \"BRL\"}",
                "{\"amount\": 100, \"currency\": \"BRL\", \"metadata\": {\"order\": 17}}")) {
            assertThat(create(body))
                    .as(body)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("bad_request");
        }
        assertThat(charges()).isZero();
    }

    @Test
    void replaysARetryByteForByte() {
        MvcTestResult first = create(BODY);
        MvcTestResult retry = create(BODY);

        assertThat(retry)
                .hasStatus(HttpStatus.CREATED)
                .hasHeader("Idempotent-Replayed", "true")
                .hasHeader(
                        "Location", Objects.requireNonNull(first.getResponse().getHeader("Location")));
        assertThat(retry.getResponse().getContentAsByteArray())
                .isEqualTo(first.getResponse().getContentAsByteArray());
        assertThat(charges()).isOne();
    }

    @Test
    void createsAnotherChargeForAnotherKey() {
        create(key, "order-1", BODY);
        create(key, "order-2", BODY);

        assertThat(charges()).isEqualTo(2);
    }

    @Test
    void requiresTheIdempotencyKey() {
        MvcTestResult result = send(mvc.post()
                .uri("/v1/charges")
                .header("Authorization", "Bearer " + key.secret())
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("idempotency_key_required");
    }

    @Test
    void requiresTheWriteScope() {
        MvcTestResult result = create(issued(Environment.TEST, Permission.CHARGES_READ), "order-1", BODY);

        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
        assertThat(charges()).isZero();
    }

    private MvcTestResult create(String body) {
        return create(key, "order-1", body);
    }

    private MvcTestResult create(IssuedApiKey caller, String idempotencyKey, String body) {
        return send(mvc.post()
                .uri("/v1/charges")
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", idempotencyKey + ":" + body.hashCode())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MvcTestResult send(MockMvcTester.MockMvcRequestBuilder request) {
        MvcTestResult result = request.exchange();
        contract.assertConforms(result);
        return result;
    }

    private JsonNode body(MvcTestResult result) {
        return json.readTree(result.getResponse().getContentAsByteArray());
    }

    private static Duration lifetime(JsonNode charge) {
        return Duration.between(
                Instant.parse(charge.path("created_at").asString()),
                Instant.parse(charge.path("expires_at").asString()));
    }

    private IssuedApiKey issued(Environment environment, Permission scope) {
        return AccessFixtures.issue(access, AccessFixtures.admin(organization, environment), "Checkout", Set.of(scope));
    }

    private long charges() {
        return Objects.requireNonNull(owner.jdbc()
                .sql("SELECT count(*) FROM billing.charges WHERE tenant_id IN (?, ?)")
                .params(organization.test().value(), organization.live().value())
                .query(Long.class)
                .single());
    }
}
