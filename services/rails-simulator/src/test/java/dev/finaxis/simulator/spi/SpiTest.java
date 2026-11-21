package dev.finaxis.simulator.spi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.finaxis.simulator.PostgresTestConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@SpringBootTest
@Import({PostgresTestConfiguration.class, SpiTest.FixedClock.class})
class SpiTest {

    private static final Instant NOW = Instant.parse("2026-11-21T14:30:12.345678Z");
    private static final List<Ispb> PAYERS = List.of(new Ispb("99990011"), new Ispb("99990012"), new Ispb("99990013"));

    private final Spi spi;
    private final PaymentRepository payments;

    @Autowired
    SpiTest(Spi spi, PaymentRepository payments) {
        this.spi = spi;
        this.payments = payments;
    }

    @Test
    void recordsTheSettledPayment() {
        var payment = spi.settle(10_000, "tx1", "000201");

        assertThat(spi.find(payment.endToEndId())).contains(payment);
        assertThat(payment.amountMinor()).isEqualTo(10_000);
        assertThat(payment.transactionId()).isEqualTo("tx1");
        assertThat(payment.brCode()).isEqualTo("000201");
    }

    @Test
    void settlesAtTheClocksMillisecond() {
        var payment = spi.settle(100, "tx2", "000201");

        assertThat(payment.settledAt()).isEqualTo(Instant.parse("2026-11-21T14:30:12.345Z"));
        assertThat(payment.endToEndId().value()).contains("202611211430");
    }

    @Test
    void isDebitedByOneOfThePayersInstitutions() {
        var payment = spi.settle(100, "tx3", "000201");

        assertThat(PAYERS).contains(payment.debtorInstitution());
        assertThat(payment.endToEndId().value())
                .startsWith("E" + payment.debtorInstitution().digits());
    }

    @Test
    void settlesTheSameCodeTwiceAsTwoPayments() {
        var first = spi.settle(100, "tx4", "000201");
        var second = spi.settle(100, "tx4", "000201");

        assertThat(first.endToEndId()).isNotEqualTo(second.endToEndId());
    }

    @Test
    void writesNothingForAnEndToEndIdAlreadyTaken() {
        var payment = spi.settle(100, "tx5", "000201");
        var collision = new Payment(
                payment.endToEndId(), "tx6", 200, payment.debtorInstitution(), "000201", payment.settledAt());

        assertThat(payments.insertIfAbsent(collision)).isFalse();
        assertThat(spi.find(payment.endToEndId())).contains(payment);
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
