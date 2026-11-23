package dev.finaxis.core.billing.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.core.OpenApiContract;
import dev.finaxis.core.PostgresTestConfiguration;
import dev.finaxis.core.access.AccessApi;
import dev.finaxis.core.access.AccessFixtures;
import dev.finaxis.core.access.IssuedApiKey;
import dev.finaxis.core.access.TenantBoundAccess;
import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.shared.Environment;
import dev.finaxis.core.shared.UuidV7Generator;
import dev.finaxis.core.tenancy.NewOrganization;
import dev.finaxis.core.tenancy.Permission;
import dev.finaxis.core.tenancy.TenancyApi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Locale;
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
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /v1/charges/{id}} (billing design, section 12.2), every response checked against the contract. */
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
    private final JsonMapper json;
    private final OpenApiContract contract = OpenApiContract.v1();

    private NewOrganization organization;
    private IssuedApiKey writer;
    private IssuedApiKey reader;

    @Autowired
    ReadChargesControllerTest(MockMvcTester mvc, AccessApi access, TenancyApi tenancy, JsonMapper json) {
        this.mvc = mvc;
        this.access = new TenantBoundAccess(access, tenancy);
        this.tenancy = tenancy;
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
        assertThat(result)
                .bodyJson()
                .isStrictlyEqualTo(new String(created.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
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
