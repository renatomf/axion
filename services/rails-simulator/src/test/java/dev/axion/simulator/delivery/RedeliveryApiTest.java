package dev.axion.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.simulator.PostgresTestConfiguration;
import dev.axion.simulator.delivery.StubCore.Received;
import dev.axion.simulator.delivery.StubCore.Reply;
import dev.axion.simulator.spi.Debtor;
import dev.axion.simulator.spi.Payment;
import dev.axion.simulator.spi.PaymentOrder;
import dev.axion.simulator.spi.Spi;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** Manual redelivery of {@code openapi/simulator.yaml} (rails simulator design, section 7.4), against a stub core. */
@SpringBootTest(properties = "simulator.dispatcher.enabled=true")
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class RedeliveryApiTest {

    private static final String AUTHORIZATION = "Bearer operator-token-for-tests-only-0000";
    private static final StubCore CORE = new StubCore();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final MockMvcTester mvc;
    private final Spi spi;
    private final Deliveries deliveries;

    @Autowired
    RedeliveryApiTest(MockMvcTester mvc, Spi spi, Deliveries deliveries) {
        this.mvc = mvc;
        this.spi = spi;
        this.deliveries = deliveries;
    }

    @DynamicPropertySource
    static void core(DynamicPropertyRegistry properties) {
        properties.add("simulator.core.url", CORE::url);
    }

    @AfterAll
    static void stopCore() {
        CORE.close();
    }

    @Test
    void sendsARefusedDeliveryAgainWithItsBytes() {
        CORE.reply("redeliver1", Reply.status(400));
        Payment payment = pay("redeliver1");
        await().atMost(WAIT).until(() -> first(payment).status() == DeliveryStatus.REFUSED);

        MvcTestResult result = redeliver(first(payment).id().toString());

        assertThat(result).hasStatus(HttpStatus.CREATED).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("/pix/payments/" + payment.endToEndId());
        assertThat(result)
                .bodyJson()
                .extractingPath("$.endToEndId")
                .isEqualTo(payment.endToEndId().value());
        await().atMost(WAIT).until(() -> all(payment).getLast().status() == DeliveryStatus.DELIVERED);
        List<Delivery> all = all(payment);
        assertThat(all).extracting(Delivery::kind).containsExactly("original", "redelivery");
        assertThat(all).extracting(Delivery::status).containsExactly(DeliveryStatus.REFUSED, DeliveryStatus.DELIVERED);
        assertThat(result)
                .bodyJson()
                .extractingPath("$.id")
                .isEqualTo(all.getLast().id().toString());
        List<Received> requests = CORE.receivedFor("redeliver1");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
        assertThat(requests.get(1).header("x-correlation-id"))
                .isEqualTo(requests.get(0).header("x-correlation-id"));
    }

    @Test
    void refusesToRedeliverADeliveryTheCoreAccepted() {
        Payment payment = pay("redeliver2");
        await().atMost(WAIT).until(() -> first(payment).status() == DeliveryStatus.DELIVERED);

        assertThat(redeliver(first(payment).id().toString()))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Only a refused or failed delivery can be redelivered; this one is delivered.");
        assertThat(all(payment)).hasSize(1);
    }

    @Test
    void answers404ForADeliveryThatDoesNotExist() {
        assertThat(redeliver("0b8a4f2e-3c1d-4e5f-8a9b-0c1d2e3f4a5b")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(redeliver("not-a-uuid")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void requiresTheOperatorToken() {
        assertThat(mvc.post().uri("/deliveries/0b8a4f2e-3c1d-4e5f-8a9b-0c1d2e3f4a5b/redeliver"))
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    private Payment pay(String transactionId) {
        return spi.settle(
                new PaymentOrder(10_000, transactionId, null, "000201", new Debtor("Ana Souza", "52601815906"), null));
    }

    private List<Delivery> all(Payment payment) {
        return deliveries.of(payment.endToEndId().value());
    }

    private Delivery first(Payment payment) {
        return all(payment).getFirst();
    }

    private MvcTestResult redeliver(String id) {
        return mvc.post()
                .uri("/deliveries/" + id + "/redeliver")
                .header(HttpHeaders.AUTHORIZATION, AUTHORIZATION)
                .exchange();
    }
}
