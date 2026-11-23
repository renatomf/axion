package dev.finaxis.core.billing.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.DatabaseOwner;
import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.billing.BillingFixtures;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.rails.PixCredit;
import dev.finaxis.core.rails.PixCreditHandler;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.PublicId;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /v1/charges/{id}} and {@code GET /v1/charges} (billing design, section 12.2), every
 * response checked against the contract.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class ReadChargesControllerTest {

    private static final String BODY = """
            {
              "amount": 10000,
              "currency": "BRL",
              "description": "Mensalidade de novembro",
              "metadata": {"order_id": "ord_8842"}
            }
            """;

    private final MockMvcTester mvc;
    private final AccessApi access;
    private final TenancyApi tenancy;
    private final JdbcClient owner;
    private final JsonMapper json;
    private final PixCreditHandler settlement;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey writer;
    private IssuedApiKey reader;

    @Autowired
    ReadChargesControllerTest(
            MockMvcTester mvc,
            AccessApi access,
            TenancyApi tenancy,
            DatabaseOwner owner,
            JsonMapper json,
            PixCreditHandler settlement) {
        this.mvc = mvc;
        this.settlement = settlement;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
        this.owner = owner.jdbc();
        this.json = json;
    }

    @BeforeEach
    void issueKeys() {
        organization = tenancy.createOrganization("Acme Ltda", UUID.randomUUID().toString());
        writer = issued(Environment.TEST, Permission.CHARGES_WRITE);
        reader = issued(Environment.TEST, Permission.CHARGES_READ);
    }

    @Test
    void readsTheChargeAsItsCreationReturnedIt() {
        MvcTestResult created = create(writer, BODY);

        MvcTestResult result = get(reader, "/v1/charges/" + id(created));

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        var expected = (ObjectNode) json.readTree(body(created));
        expected.putArray("payments");
        expected.putArray("refunds");
        assertThat(result).bodyJson().isStrictlyEqualTo(expected.toString());
    }

    @Test
    void showsThePaymentsTheChargeReceived() {
        MvcTestResult created = create(writer, BODY);
        var accepted = Instant.parse("2026-11-22T14:41:07.512Z");
        var credit = new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
                json.readTree(body(created)).path("pix").path("transaction_id").asString(),
                BillingFixtures.brl(10_000),
                accepted,
                "99990011");
        settlement.handle(credit);

        MvcTestResult result = get(reader, "/v1/charges/" + id(created));

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode charge = json.readTree(body(result));
        assertThat(charge.path("status").asString()).isEqualTo("paid");
        assertThat(charge.path("payments")).hasSize(1);
        JsonNode payment = charge.path("payments").path(0);
        assertThat(payment.path("id").asString()).startsWith("pmt_");
        assertThat(payment)
                .isEqualTo(json.readTree("""
                        {
                          "id": "%s",
                          "end_to_end_id": "%s",
                          "amount": 10000,
                          "amount_refunded": 0,
                          "accepted_at": "2026-11-22T14:41:07.512Z",
                          "outcome": "settled",
                          "review_reason": null
                        }
                        """.formatted(payment.path("id").asString(), credit.endToEndId())));
    }

    @Test
    void showsTheRefundsAsTheirCreationReturnedThem() {
        MvcTestResult created = create(writer, BODY);
        settlement.handle(new PixCredit(
                BillingFixtures.endToEndId(),
                "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
                json.readTree(body(created)).path("pix").path("transaction_id").asString(),
                BillingFixtures.brl(10_000),
                Instant.now(),
                "99990011"));
        IssuedApiKey refunder = issued(Environment.TEST, Permission.REFUNDS_WRITE);
        MvcTestResult first = refund(refunder, id(created), """
                {"amount": 2500}
                """);
        MvcTestResult second = refund(refunder, id(created), """
                {"amount": 1500}
                """);

        MvcTestResult result = get(reader, "/v1/charges/" + id(created));

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode charge = json.readTree(body(result));
        assertThat(charge.path("status").asString()).isEqualTo("partially_refunded");
        assertThat(charge.path("amount_refunded").asLong()).isEqualTo(4_000);
        assertThat(charge.path("payments").path(0).path("amount_refunded").asLong())
                .isEqualTo(4_000);
        assertThat(charge.path("refunds")).containsExactly(json.readTree(body(first)), json.readTree(body(second)));
    }

    @Test
    void answersNotFoundForAnUnknownCharge() {
        var unknown = new ChargeId(new UuidV7Generator(Clock.systemUTC()).next());

        assertNotFound(get(reader, "/v1/charges/" + unknown.publicId()));
    }

    @Test
    void answersNotFoundForAMalformedId() {
        String id = id(create(writer, BODY));

        for (String malformed :
                new String[] {"ch_x", id.toUpperCase(Locale.ROOT), "key_" + id.substring(3), id + "0"}) {
            assertNotFound(get(reader, "/v1/charges/" + malformed));
        }
    }

    @Test
    void answersNotFoundForAChargeOfTheOtherEnvironment() {
        String id = id(create(writer, BODY));

        assertNotFound(get(issued(Environment.LIVE, Permission.CHARGES_READ), "/v1/charges/" + id));
    }

    @Test
    void requiresTheReadScope() {
        String id = id(create(writer, BODY));

        assertThat(get(writer, "/v1/charges/" + id))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    @Test
    void listsTheChargesNewestFirstAsTheirCreationReturnedThem() {
        String first = body(create(writer, BODY));
        String second = body(create(writer, BODY));

        MvcTestResult result = list(reader);

        assertThat(result).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().isStrictlyEqualTo("""
                {"object": "list", "data": [%s, %s], "has_more": false}
                """.formatted(second, first));
    }

    @Test
    void listsNoChargeInAnEmptyList() {
        assertThat(list(reader)).bodyJson().isStrictlyEqualTo("""
                {"object": "list", "data": [], "has_more": false}
                """);
    }

    @Test
    void pagesThroughTheChargesStartingAfterTheLastOne() {
        String oldest = id(create(writer, BODY));
        String middle = id(create(writer, BODY));
        String newest = id(create(writer, BODY));

        MvcTestResult first = list(reader, "limit", "2");
        MvcTestResult second = list(reader, "limit", "2", "starting_after", middle);

        assertThat(first).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(newest, middle));
        assertThat(first).bodyJson().extractingPath("$.has_more").isEqualTo(true);
        assertThat(second).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(oldest));
        assertThat(second).bodyJson().extractingPath("$.has_more").isEqualTo(false);
    }

    @Test
    void listsOnlyTheChargesOfTheStatus() {
        String expired = id(create(writer, BODY));
        id(create(writer, BODY));
        owner.sql("UPDATE billing.charges SET status = 'EXPIRED' WHERE id = ?")
                .param(PublicId.parse(ChargeId.PREFIX, expired).uuid())
                .update();

        MvcTestResult result = list(reader, "status", "expired");

        assertThat(result).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(expired));
        assertThat(result).bodyJson().extractingPath("$.data[0].status").isEqualTo("expired");
    }

    @Test
    void listsOnlyTheChargesCreatedInTheRange() {
        String inRange = id(create(writer, BODY));
        id(create(writer, BODY));
        owner.sql("UPDATE billing.charges SET created_at = ? WHERE id = ?")
                .param(OffsetDateTime.parse("2026-11-01T12:00:00Z"))
                .param(PublicId.parse(ChargeId.PREFIX, inRange).uuid())
                .update();

        MvcTestResult result =
                list(reader, "created[gte]", "2026-11-01T09:00:00-03:00", "created[lt]", "2026-11-02T00:00:00Z");

        assertThat(result).bodyJson().extractingPath("$.data[*].id").isEqualTo(List.of(inRange));
    }

    @Test
    void listsNoChargeOfTheOtherEnvironment() {
        create(writer, BODY);

        assertThat(list(issued(Environment.LIVE, Permission.CHARGES_READ)))
                .bodyJson()
                .extractingPath("$.data")
                .isEqualTo(List.of());
    }

    @Test
    void refusesParametersOutsideTheirRulesNamingEach() {
        Map<List<String>, List<String>> invalid = Map.of(
                List.of("limit", "0"), List.of("limit"),
                List.of("limit", "101"), List.of("limit"),
                List.of("status", "ACTIVE"), List.of("status"),
                List.of("starting_after", "ch_x"), List.of("starting_after"),
                List.of("limit", "0", "status", "open"), List.of("limit", "status"));

        invalid.forEach((parameters, fields) -> assertThat(list(reader, parameters.toArray(String[]::new)))
                .as(parameters.toString())
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .satisfies(problem -> {
                    assertThat(problem).extractingPath("$.code").isEqualTo("validation_failed");
                    assertThat(problem).extractingPath("$.errors[*].field").isEqualTo(fields);
                }));
    }

    @Test
    void refusesAParameterOfAnotherType() {
        for (List<String> parameters : List.of(
                List.of("limit", "ten"), List.of("created[gte]", "2026-11-01"), List.of("created[lt]", "now"))) {
            assertThat(list(reader, parameters.toArray(String[]::new)))
                    .as(parameters.toString())
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("bad_request");
        }
    }

    @Test
    void requiresTheReadScopeToList() {
        assertThat(list(writer, "limit", "0"))
                .hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("insufficient_permission");
    }

    /** {@code GET /v1/charges} with the parameters, given as name, value, name, value… */
    private MvcTestResult list(IssuedApiKey caller, String... parameters) {
        var request = mvc.get().uri("/v1/charges").header("Authorization", "Bearer " + caller.secret());
        for (int i = 0; i < parameters.length; i += 2) {
            request = request.param(parameters[i], parameters[i + 1]);
        }
        MvcTestResult result = request.exchange();
        contract.assertConforms(result);
        return result;
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private MvcTestResult create(IssuedApiKey caller, String body) {
        MvcTestResult result = mvc.post()
                .uri("/v1/charges")
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        contract.assertConforms(result);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult refund(IssuedApiKey caller, String charge, String body) {
        MvcTestResult result = mvc.post()
                .uri("/v1/charges/" + charge + "/refunds")
                .header("Authorization", "Bearer " + caller.secret())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
        contract.assertConforms(result);
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private MvcTestResult get(IssuedApiKey caller, String uri) {
        MvcTestResult result = mvc.get()
                .uri(uri)
                .header("Authorization", "Bearer " + caller.secret())
                .exchange();
        contract.assertConforms(result);
        return result;
    }

    private String id(MvcTestResult created) {
        return json.readTree(created.getResponse().getContentAsByteArray())
                .path("id")
                .asString();
    }

    private IssuedApiKey issued(Environment environment, Permission scope) {
        return AccessFixtures.issue(access, AccessFixtures.admin(organization, environment), "Checkout", Set.of(scope));
    }

    private static void assertNotFound(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("not_found");
    }
}
