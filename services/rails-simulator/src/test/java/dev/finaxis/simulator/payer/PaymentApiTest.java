package dev.finaxis.simulator.payer;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** The payer API of {@code openapi/simulator.yaml}, over HTTP, with the operator token and a real database. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class PaymentApiTest {

    private static final String AUTHORIZATION = "Bearer operator-token-for-tests-only-0000";
    private static final String END_TO_END_ID = "E[0-9]{8}[0-9]{12}[A-Za-z0-9]{11}";
    private static final String MILLISECONDS = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z";

    private final MockMvcTester mvc;

    @Autowired
    PaymentApiTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    @Test
    void paysABrCode() {
        MvcTestResult result = pay("{\"brCode\": \"" + BrCodeTest.PAYABLE + "\"}");

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result).bodyJson().extractingPath("$.endToEndId").asString().matches(END_TO_END_ID);
        assertThat(result).bodyJson().extractingPath("$.settledAt").asString().matches(MILLISECONDS);
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

    private MvcTestResult get(String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();
    }
}
