package dev.finaxis.simulator.spi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import({PostgresTestConfiguration.class, SpiTest.FixedClock.class})
class SpiTest {

    private static final Instant NOW = Instant.parse("2026-11-21T14:30:12.345678Z");
    private static final List<Ispb> PAYERS = List.of(new Ispb("99990011"), new Ispb("99990012"), new Ispb("99990013"));

    private static final Debtor ANA = new Debtor("Ana Souza", "52601815906");

    private final Spi spi;
    private final PaymentRepository payments;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final TransactionTemplate transaction;

    @Autowired
    SpiTest(Spi spi, PaymentRepository payments, JdbcClient jdbc, JsonMapper json, TransactionTemplate transaction) {
        this.spi = spi;
        this.payments = payments;
        this.jdbc = jdbc;
        this.json = json;
        this.transaction = transaction;
    }

    @Test
    void recordsTheSettledPayment() {
        var payment = spi.settle(order(10_000, "tx1"));

        assertThat(spi.find(payment.endToEndId())).contains(payment);
        assertThat(payment.amountMinor()).isEqualTo(10_000);
        assertThat(payment.transactionId()).isEqualTo("tx1");
        assertThat(payment.brCode()).isEqualTo("000201");
    }

    @Test
    void settlesAtTheClocksMillisecond() {
        var payment = spi.settle(order(100, "tx2"));

        assertThat(payment.settledAt()).isEqualTo(Instant.parse("2026-11-21T14:30:12.345Z"));
        assertThat(payment.endToEndId().value()).contains("202611211430");
    }

    @Test
    void isDebitedByOneOfThePayersInstitutions() {
        var payment = spi.settle(order(100, "tx3"));

        assertThat(PAYERS).contains(payment.debtorInstitution());
        assertThat(payment.endToEndId().value())
                .startsWith("E" + payment.debtorInstitution().digits());
    }

    @Test
    void settlesTheSameCodeTwiceAsTwoPayments() {
        var first = spi.settle(order(100, "tx4"));
        var second = spi.settle(order(100, "tx4"));

        assertThat(first.endToEndId()).isNotEqualTo(second.endToEndId());
    }

    @Test
    void writesNothingForAnEndToEndIdAlreadyTaken() {
        var payment = spi.settle(order(100, "tx5"));
        var collision = new Payment(
                payment.endToEndId(), "tx6", 200, payment.debtorInstitution(), "000201", payment.settledAt());

        assertThat(payments.insertIfAbsent(collision)).isFalse();
        assertThat(spi.find(payment.endToEndId())).contains(payment);
    }

    @Test
    void recordsTheCreditNotificationWithThePayment() {
        var payment = spi.settle(new PaymentOrder(10_000, "tx7", "pix@example.com", "000201", ANA, "Mensalidade"));

        JsonNode message = notification(payment);
        assertThat(message.path("messageId").asString()).matches("M" + payment.debtorInstitution() + "[A-Za-z0-9]{23}");
        assertThat(message.path("messageType").asString()).isEqualTo("pacs.008");
        assertThat(message.path("creationDateTime").asString()).isEqualTo("2026-11-21T14:30:12.345Z");
        assertThat(message.path("endToEndId").asString())
                .isEqualTo(payment.endToEndId().value());
        assertThat(message.path("transactionId").asString()).isEqualTo("tx7");
        assertThat(message.path("localInstrument").asString()).isEqualTo("QRDN");
        assertThat(message.path("interbankSettlementAmount").path("amount").asString())
                .isEqualTo("100.00");
        assertThat(message.path("interbankSettlementAmount").path("currency").asString())
                .isEqualTo("BRL");
        assertThat(message.path("acceptanceDateTime").asString()).isEqualTo("2026-11-21T14:30:12.345Z");
        assertThat(message.path("debtor").path("name").asString()).isEqualTo("Ana Souza");
        assertThat(message.path("debtor").path("taxId").asString()).isEqualTo("52601815906");
        assertThat(message.path("debtorAccount").path("branch").asString()).isEqualTo("0001");
        assertThat(message.path("debtorAccount").path("number").asString()).matches("[0-9]{1,6}");
        assertThat(message.path("debtorAccount").path("type").asString()).isEqualTo("CACC");
        assertThat(message.path("debtorAgent").path("ispb").asString())
                .isEqualTo(payment.debtorInstitution().digits());
        assertThat(message.path("creditorAgent").path("ispb").asString()).isEqualTo("99990001");
        assertThat(message.path("creditorAccount").path("proxy").asString()).isEqualTo("pix@example.com");
        assertThat(message.path("remittanceInformation").asString()).isEqualTo("Mensalidade");
    }

    @Test
    void leavesOutTheProxyAndTheMessageThePaymentLacks() {
        var payment = spi.settle(order(100, "tx8"));

        JsonNode message = notification(payment);
        assertThat(message.has("creditorAccount")).isFalse();
        assertThat(message.has("remittanceInformation")).isFalse();
    }

    @Test
    void schedulesTheNotificationForNow() {
        var payment = spi.settle(order(100, "tx9"));

        assertThat(jdbc.sql("""
                        SELECT status || ' ' || kind || ' ' || attempts || ' ' || (next_attempt_at = created_at)
                        FROM deliveries WHERE end_to_end_id = ?
                        """)
                        .param(payment.endToEndId().value())
                        .query(String.class)
                        .single())
                .isEqualTo("pending original 0 true");
    }

    @Test
    void commitsThePaymentAndItsNotificationTogetherOrNotAtAll() {
        Payment payment = Objects.requireNonNull(transaction.execute(status -> {
            status.setRollbackOnly();
            return spi.settle(order(100, "tx10"));
        }));

        assertThat(spi.find(payment.endToEndId())).isEmpty();
        assertThat(deliveriesOf(payment)).isZero();
    }

    private static PaymentOrder order(long amountMinor, String transactionId) {
        return new PaymentOrder(amountMinor, transactionId, null, "000201", ANA, null);
    }

    private JsonNode notification(Payment payment) {
        byte[] message = jdbc.sql("SELECT message FROM deliveries WHERE end_to_end_id = ?")
                .param(payment.endToEndId().value())
                .query(byte[].class)
                .single();
        return json.readTree(new String(message, StandardCharsets.UTF_8));
    }

    private long deliveriesOf(Payment payment) {
        return jdbc.sql("SELECT count(*) FROM deliveries WHERE end_to_end_id = ?")
                .param(payment.endToEndId().value())
                .query(Long.class)
                .single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
