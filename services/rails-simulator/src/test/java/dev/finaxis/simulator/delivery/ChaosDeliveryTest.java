package dev.finaxis.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.finaxis.simulator.PostgresTestConfiguration;
import dev.finaxis.simulator.chaos.AppliedFault;
import dev.finaxis.simulator.chaos.ChaosRules;
import dev.finaxis.simulator.chaos.Fault;
import dev.finaxis.simulator.chaos.FaultRule;
import dev.finaxis.simulator.chaos.Match;
import dev.finaxis.simulator.delivery.StubCore.Received;
import dev.finaxis.simulator.spi.Debtor;
import dev.finaxis.simulator.spi.Payment;
import dev.finaxis.simulator.spi.PaymentOrder;
import dev.finaxis.simulator.spi.Spi;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The faults applied at each attempt (rails simulator design, section 7.2), against a stub core over
 * HTTP. Each test matches its own transaction ID, so a rule never reaches another test's payments.
 */
@SpringBootTest(properties = "simulator.dispatcher.enabled=true")
@Import(PostgresTestConfiguration.class)
class ChaosDeliveryTest {

    private static final StubCore CORE = new StubCore();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Spi spi;
    private final Deliveries deliveries;
    private final ChaosRules chaos;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    @Autowired
    ChaosDeliveryTest(Spi spi, Deliveries deliveries, ChaosRules chaos, JdbcClient jdbc, MeterRegistry meters) {
        this.spi = spi;
        this.deliveries = deliveries;
        this.chaos = chaos;
        this.jdbc = jdbc;
        this.meters = meters;
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
    void failsAnAttemptWithoutSendingIt() {
        FaultRule rule = chaos.add(Fault.FAIL, null, null, new Match(null, "chaosFail1"), 1);
        Payment payment = pay("chaosFail1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        assertThat(attempts(payment)).containsExactly("1 retried not_sent", "2 delivered 200");
        assertThat(CORE.receivedFor("chaosFail1")).hasSize(1);
        assertThat(delivery(payment).faults()).containsExactly(new AppliedFault(rule.id(), Fault.FAIL, 1, null));
    }

    @Test
    void appliesARuleToExactlyItsTimes() {
        chaos.add(Fault.FAIL, null, null, new Match(null, "chaosFail2"), 2);
        Payment failed = pay("chaosFail2");
        Payment untouched = pay("chaosFail3");

        await().atMost(WAIT).until(() -> delivery(failed).status() == DeliveryStatus.DELIVERED);
        await().atMost(WAIT).until(() -> delivery(untouched).status() == DeliveryStatus.DELIVERED);
        assertThat(attempts(failed)).containsExactly("1 retried not_sent", "2 retried not_sent", "3 delivered 200");
        assertThat(delivery(failed).faults()).extracting(AppliedFault::attempt).containsExactly(1, 2);
        assertThat(attempts(untouched)).containsExactly("1 delivered 200");
        assertThat(delivery(untouched).faults()).isEmpty();
        assertThat(chaos.list()).isEmpty();
    }

    @Test
    void sendsAnAttemptAndDiscardsTheAnswer() {
        FaultRule rule = chaos.add(Fault.LOST_RESPONSE, null, null, new Match(null, "chaosLost1"), 1);
        Payment payment = pay("chaosLost1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        assertThat(attempts(payment)).containsExactly("1 retried 200", "2 delivered 200");
        List<Received> requests = CORE.receivedFor("chaosLost1");
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
        assertThat(delivery(payment).faults())
                .containsExactly(new AppliedFault(rule.id(), Fault.LOST_RESPONSE, 1, null));
    }

    @Test
    void waitsWithinTheLatencyBeforeSending() {
        Duration latency = Duration.ofMillis(500);
        FaultRule rule = chaos.add(Fault.LATENCY, latency, latency, new Match(null, "chaosLatency1"), 1);
        Payment payment = pay("chaosLatency1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        Delivery delivery = delivery(payment);
        assertThat(attempts(payment)).containsExactly("1 delivered 200");
        assertThat(delivery.faults()).containsExactly(new AppliedFault(rule.id(), Fault.LATENCY, 1, latency));
        assertThat(Duration.between(
                        delivery.createdAt(), delivery.attempts().getFirst().attemptedAt()))
                .isGreaterThanOrEqualTo(latency);
    }

    @Test
    void countsEachFaultApplied() {
        double before = faultsCounted("lost_response");
        chaos.add(Fault.LOST_RESPONSE, null, null, new Match(null, "chaosCount1"), 2);
        Payment payment = pay("chaosCount1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        assertThat(faultsCounted("lost_response")).isEqualTo(before + 2);
    }

    private double faultsCounted(String fault) {
        return meters.counter(Dispatcher.FAULTS, "fault", fault).count();
    }

    private Payment pay(String transactionId) {
        return spi.settle(
                new PaymentOrder(10_000, transactionId, null, "000201", new Debtor("Ana Souza", "52601815906"), null));
    }

    private Delivery delivery(Payment payment) {
        return deliveries.of(payment.endToEndId().value()).getFirst();
    }

    private List<String> attempts(Payment payment) {
        return jdbc.sql("""
                        SELECT a.number || ' ' || a.outcome || ' ' || coalesce(a.response_status::text, a.error)
                        FROM delivery_attempts a JOIN deliveries d ON d.id = a.delivery_id
                        WHERE d.end_to_end_id = ?
                        ORDER BY a.number
                        """)
                .param(payment.endToEndId().value())
                .query((row, number) -> row.getString(1))
                .list();
    }
}
