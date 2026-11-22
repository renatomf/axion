package dev.finaxis.simulator.payer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import dev.finaxis.simulator.chaos.Outage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The payer API of {@code openapi/simulator.yaml}, over HTTP, with the operator token and a real database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PaymentApiTest {

    private static final String AUTHORIZATION = "Bearer operator-token-for-tests-only-0000";
    private static final String END_TO_END_ID = "E[0-9]{8}[0-9]{12}[A-Za-z0-9]{11}";
    private static final String MILLISECONDS = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z";

    private final MockMvcTester mvc;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final Outage outage;

    @Autowired
    PaymentApiTest(MockMvcTester mvc, JdbcClient jdbc, JsonMapper json, Outage outage) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.json = json;
        this.outage = outage;
    }

    @AfterEach
    void railsBack() {
        outage.end();
    }

    @Test
    void paysABrCode() {
        MvcTestResult result = pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}");

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.endToEndId").asString().matches(END_TO_END_ID);
        assertThat(result).bodyJson().extractingPath("$.settledAt").asString().matches(MILLISECONDS);
        assertThat(result).bodyJson().extractingPath("$.delivery").isEqualTo("pending");
    }

    @Test
    void showsThePaymentsDeliveryToTheCore() {
        String location = location(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}"));

        MvcTestResult payment = get(location);

        assertThat(payment).bodyJson().extractingPath("$.deliveries.length()").isEqualTo(1);
        assertThat(payment).bodyJson().extractingPath("$.deliveries[0].kind").isEqualTo("original");
        assertThat(payment).bodyJson().extractingPath("$.deliveries[0].status").isEqualTo("pending");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].createdAt")
                .asString()
                .matches(MILLISECONDS);
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].nextAttemptAt")
                .asString()
                .matches(MILLISECONDS);
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts")
                .asArray()
                .isEmpty();
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults")
                .asArray()
                .isEmpty();
    }

    @Test
    void showsTheFaultsAppliedToEachDelivery() {
        String location = location(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}"));
        jdbc.sql("""
                        UPDATE deliveries SET faults = '[
                            {"rule": "6f1c2a7e-0d4b-4c39-9a51-3e8b7f2d5c10", "fault": "latency", "attempt": 1, "waited": "PT1.25S"},
                            {"rule": "0b9e4d3c-5a2f-4e18-8c76-1d2f3a4b5c6d", "fault": "fail", "attempt": 1}
                        ]' WHERE end_to_end_id = ?
                        """).param(location.substring(location.lastIndexOf('/') + 1)).update();

        MvcTestResult payment = get(location);

        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults[0].rule")
                .isEqualTo("6f1c2a7e-0d4b-4c39-9a51-3e8b7f2d5c10");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults[0].fault")
                .isEqualTo("latency");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults[0].attempt")
                .isEqualTo(1);
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults[0].waited")
                .isEqualTo("PT1.25S");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].faults[1].fault")
                .isEqualTo("fail");
        assertThat(payment).bodyJson().doesNotHavePath("$.deliveries[0].faults[1].waited");
    }

    @Test
    void showsEveryAttemptInOrder() {
        String location = location(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}"));
        String endToEndId = location.substring(location.lastIndexOf('/') + 1);
        jdbc.sql("""
                        INSERT INTO delivery_attempts (delivery_id, number, attempted_at, response_status, error, outcome)
                        SELECT id, 1, now(), NULL, 'timeout', 'retried' FROM deliveries WHERE end_to_end_id = :id
                        UNION ALL
                        SELECT id, 2, now(), 200, NULL, 'delivered' FROM deliveries WHERE end_to_end_id = :id
                        """).param("id", endToEndId).update();
        jdbc.sql(
                        "UPDATE deliveries SET status = 'delivered', attempts = 2, next_attempt_at = NULL WHERE end_to_end_id = ?")
                .param(endToEndId)
                .update();

        MvcTestResult payment = get(location);

        assertThat(payment).bodyJson().extractingPath("$.deliveries[0].status").isEqualTo("delivered");
        assertThat(payment).bodyJson().doesNotHavePath("$.deliveries[0].nextAttemptAt");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[0].number")
                .isEqualTo(1);
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[0].error")
                .isEqualTo("timeout");
        assertThat(payment).bodyJson().doesNotHavePath("$.deliveries[0].attempts[0].responseStatus");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[0].outcome")
                .isEqualTo("retried");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[1].responseStatus")
                .isEqualTo(200);
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[1].outcome")
                .isEqualTo("delivered");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.deliveries[0].attempts[1].attemptedAt")
                .asString()
                .matches(MILLISECONDS);
    }

    @Test
    void readsThePaymentBackFromItsLocation() {
        MvcTestResult created = pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}");
        String location = String.valueOf(created.getResponse().getHeader(HttpHeaders.LOCATION));
        String endToEndId = location.substring(location.lastIndexOf('/') + 1);

        MvcTestResult payment = get(location);

        assertThat(payment).hasStatusOk();
        assertThat(payment).bodyJson().extractingPath("$.endToEndId").isEqualTo(endToEndId);
        assertThat(payment).bodyJson().extractingPath("$.transactionId").isEqualTo("fx7Kq2mP9wR4tY6uA8sD3fG5h");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.interbankSettlementAmount.amount")
                .isEqualTo("100.00");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.interbankSettlementAmount.currency")
                .isEqualTo("BRL");
        assertThat(payment)
                .bodyJson()
                .extractingPath("$.debtorAgent.ispb")
                .asString()
                .matches("9999001[123]");
        assertThat(payment).bodyJson().extractingPath("$.settledAt").asString().matches(MILLISECONDS);
    }

    @Test
    void notifiesTheCoreOfThePayerAndTheMessage() {
        MvcTestResult created = pay("""
                {"brCode": "%s", "payer": {"name": "Bruno Lima", "taxId": "11144477735"}, "message": "Mensalidade"}
                """.formatted(BrCodeTest.PAYABLE));

        JsonNode notification = notification(created);
        assertThat(notification.path("debtor").path("name").asString()).isEqualTo("Bruno Lima");
        assertThat(notification.path("debtor").path("taxId").asString()).isEqualTo("11144477735");
        assertThat(notification.path("remittanceInformation").asString()).isEqualTo("Mensalidade");
        assertThat(notification.path("creditorAccount").path("proxy").asString())
                .isEqualTo("123e4567-e12b-12d1-a456-426655440000");
    }

    @Test
    void choosesASyntheticPayerWhenTheRequestNamesNone() {
        JsonNode notification = notification(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}"));

        assertThat(notification.path("debtor").path("name").asString()).isNotBlank();
        assertThat(Cpf.isValid(notification.path("debtor").path("taxId").asString()))
                .isTrue();
        assertThat(notification.has("remittanceInformation")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"taxId\": \"11144477735\"}",
                "{\"name\": \" \", \"taxId\": \"11144477735\"}",
                "{\"name\": \"Bruno Lima\"}",
                "{\"name\": \"Bruno Lima\", \"taxId\": \"11144477736\"}"
            })
    void refusesAPayerWithoutANameOrAValidCpf(String payer) {
        assertThat(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\", \"payer\": " + payer + "}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .asString()
                .startsWith("payer.");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 141})
    void refusesAMessageOutsideOneTo140Characters(int length) {
        String body = "{\"brCode\": \"" + BrCodeTest.PAYABLE + "\", \"message\": \"" + "a".repeat(length) + "\"}";

        assertThat(pay(body))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("message must have 1 to 140 characters.");
    }

    @Test
    void acceptsAMessageOf140Characters() {
        String body = "{\"brCode\": \"" + BrCodeTest.PAYABLE + "\", \"message\": \"" + "a".repeat(140) + "\"}";

        assertThat(pay(body)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void paysTheSameCodeTwiceAsTwoPayments() {
        String body = "{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}";

        String first = pay(body).getResponse().getHeader(HttpHeaders.LOCATION);
        String second = pay(body).getResponse().getHeader(HttpHeaders.LOCATION);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void refusesACodeItCannotPayWithTheRuleItBreaks() {
        String tampered = BrCodeTest.PAYABLE.replace("5406100.00", "5406900.00");

        assertThat(pay("{\"brCode\": \"" + tampered + "\"}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("The CRC (tag 63) does not match the BR Code");
    }

    @Test
    void refusesABodyWithoutABrCode() {
        assertThat(pay("{}"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("brCode is required.");
    }

    @Test
    void refusesABodyThatIsNotJson() {
        assertThat(pay("brCode=000201"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void answers404ForAnUnknownPayment() {
        assertThat(get("/pix/payments/E99990011202611211430zzzzzzzzzzz"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void answers404ForAnIdentifierThatCannotExist() {
        assertThat(get("/pix/payments/not-an-end-to-end-id")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void settlesNothingDuringAnOutage() {
        long before = payments();
        outage.start(Duration.ofSeconds(30));

        MvcTestResult refused = pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}");

        assertThat(refused)
                .hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(refused).bodyJson().extractingPath("$.detail").isEqualTo("The Pix rails are down.");
        assertThat(Integer.parseInt(String.valueOf(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER))))
                .isBetween(1, 30);
        assertThat(payments()).isEqualTo(before);
    }

    @Test
    void paysAgainOnceTheOutageEnds() {
        outage.start(null);

        MvcTestResult refused = pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}");
        outage.end();

        assertThat(refused).hasStatus(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void requiresTheOperatorToken() {
        assertThat(mvc.post()
                        .uri("/pix/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private MvcTestResult pay(String body) {
        return mvc.post()
                .uri("/pix/payments")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private long payments() {
        return jdbc.sql("SELECT count(*) FROM payments").query(Long.class).single();
    }

    private static String location(MvcTestResult created) {
        return String.valueOf(created.getResponse().getHeader(HttpHeaders.LOCATION));
    }

    private JsonNode notification(MvcTestResult created) {
        String location = String.valueOf(created.getResponse().getHeader(HttpHeaders.LOCATION));
        byte[] message = jdbc.sql("SELECT message FROM deliveries WHERE end_to_end_id = ?")
                .param(location.substring(location.lastIndexOf('/') + 1))
                .query(byte[].class)
                .single();
        return json.readTree(new String(message, StandardCharsets.UTF_8));
    }

    private MvcTestResult get(String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();
    }
}
