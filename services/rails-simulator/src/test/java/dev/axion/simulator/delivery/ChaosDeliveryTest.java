package dev.axion.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.axion.simulator.PostgresTestConfiguration;
import dev.axion.simulator.chaos.AppliedFault;
import dev.axion.simulator.chaos.ChaosRules;
import dev.axion.simulator.chaos.Fault;
import dev.axion.simulator.chaos.FaultParameters;
import dev.axion.simulator.chaos.FaultRule;
import dev.axion.simulator.chaos.Match;
import dev.axion.simulator.chaos.Outage;
import dev.axion.simulator.delivery.StubCore.Received;
import dev.axion.simulator.spi.Debtor;
import dev.axion.simulator.spi.Payment;
import dev.axion.simulator.spi.PaymentOrder;
import dev.axion.simulator.spi.Spi;
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
    private final Outage outage;
    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    @Autowired
    ChaosDeliveryTest(
            Spi spi, Deliveries deliveries, ChaosRules chaos, Outage outage, JdbcClient jdbc, MeterRegistry meters) {
        this.spi = spi;
        this.deliveries = deliveries;
        this.chaos = chaos;
        this.outage = outage;
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
        FaultRule rule = chaos.add(Fault.FAIL, FaultParameters.NONE, new Match(null, "chaosFail1"), 1);
        Payment payment = pay("chaosFail1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        assertThat(attempts(payment)).containsExactly("1 retried not_sent", "2 delivered 200");
        assertThat(CORE.receivedFor("chaosFail1")).hasSize(1);
        assertThat(delivery(payment).faults()).containsExactly(new AppliedFault(rule.id(), Fault.FAIL, 1, null));
    }

    @Test
    void appliesARuleToExactlyItsTimes() {
        chaos.add(Fault.FAIL, FaultParameters.NONE, new Match(null, "chaosFail2"), 2);
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
        FaultRule rule = chaos.add(Fault.LOST_RESPONSE, FaultParameters.NONE, new Match(null, "chaosLost1"), 1);
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
        FaultRule rule = chaos.add(
                Fault.LATENCY, FaultParameters.latency(latency, latency), new Match(null, "chaosLatency1"), 1);
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
    void postponesTheFirstAttemptByTheDelay() {
        Duration delay = Duration.ofSeconds(1);
        FaultRule rule = chaos.add(Fault.DELAY, FaultParameters.delay(delay), new Match(null, "chaosDelay1"), 1);
        Payment payment = pay("chaosDelay1");

        Delivery created = delivery(payment);
        assertThat(created.nextAttemptAt()).isEqualTo(created.createdAt().plus(delay));
        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        Delivery delivered = delivery(payment);
        assertThat(attempts(payment)).containsExactly("1 delivered 200");
        assertThat(delivered.faults()).containsExactly(new AppliedFault(rule.id(), Fault.DELAY, null, delay));
        assertThat(Duration.between(
                        delivered.createdAt(), delivered.attempts().getFirst().attemptedAt()))
                .isGreaterThanOrEqualTo(delay);
    }

    @Test
    void sendsTheCopiesOfADuplicatedDeliveryWithIdenticalBytes() {
        FaultRule rule = chaos.add(Fault.DUPLICATE, FaultParameters.duplicate(2), new Match(null, "chaosDup1"), 1);
        Payment payment = pay("chaosDup1");
        Payment untouched = pay("chaosDup1");

        await().atMost(WAIT)
                .until(() -> deliveries.of(payment.endToEndId().value()).stream()
                        .allMatch(delivery -> delivery.status() == DeliveryStatus.DELIVERED));
        List<Delivery> all = deliveries.of(payment.endToEndId().value());
        assertThat(all).extracting(Delivery::kind).containsExactly("original", "duplicate", "duplicate");
        assertThat(all)
                .allSatisfy(delivery -> assertThat(delivery.faults())
                        .containsExactly(new AppliedFault(rule.id(), Fault.DUPLICATE, null, null)));
        assertThat(all)
                .extracting(Delivery::createdAt)
                .containsOnly(all.getFirst().createdAt());
        await().atMost(WAIT).until(() -> delivery(untouched).status() == DeliveryStatus.DELIVERED);
        assertThat(deliveries.of(untouched.endToEndId().value())).hasSize(1);
        List<Received> requests = CORE.receivedFor("chaosDup1").stream()
                .filter(request -> request.body().contains(payment.endToEndId().value()))
                .toList();
        assertThat(requests).hasSize(3);
        assertThat(requests)
                .extracting(Received::body)
                .containsOnly(requests.getFirst().body());
        assertThat(requests)
                .extracting(request -> request.header("x-correlation-id"))
                .containsOnly(requests.getFirst().header("x-correlation-id"));
    }

    @Test
    void holdsDeliveriesThenReleasesThemInReverse() {
        FaultRule rule = chaos.add(Fault.REORDER, FaultParameters.NONE, new Match(null, "chaosReorder1"), 3);
        Payment first = pay("chaosReorder1");
        Payment second = pay("chaosReorder1");

        assertThat(delivery(first).status()).isEqualTo(DeliveryStatus.HELD);
        assertThat(delivery(second).nextAttemptAt()).isNull();
        Payment third = pay("chaosReorder1");

        List<Payment> payments = List.of(first, second, third);
        await().atMost(WAIT)
                .until(() ->
                        payments.stream().allMatch(payment -> delivery(payment).status() == DeliveryStatus.DELIVERED));
        assertThat(receivedOrder("chaosReorder1", payments)).containsExactly(third, second, first);
        assertThat(payments)
                .allSatisfy(payment -> assertThat(delivery(payment).faults())
                        .containsExactly(new AppliedFault(rule.id(), Fault.REORDER, null, null)));
    }

    @Test
    void releasesWhatARemovedReorderRuleHeld() {
        FaultRule rule = chaos.add(Fault.REORDER, FaultParameters.NONE, new Match(null, "chaosReorder2"), 3);
        Payment first = pay("chaosReorder2");
        Payment second = pay("chaosReorder2");

        chaos.remove(rule.id());

        List<Payment> payments = List.of(first, second);
        await().atMost(WAIT)
                .until(() ->
                        payments.stream().allMatch(payment -> delivery(payment).status() == DeliveryStatus.DELIVERED));
        assertThat(receivedOrder("chaosReorder2", payments)).containsExactly(second, first);
    }

    @Test
    void sendsNothingDuringAnOutageAndDrainsWhenItEnds() {
        outage.start(null);
        try {
            Payment payment = pay("chaosOutage1");

            await().during(Duration.ofSeconds(1))
                    .atMost(Duration.ofSeconds(2))
                    .until(() -> attempts(payment).isEmpty());
            assertThat(delivery(payment).status()).isEqualTo(DeliveryStatus.PENDING);
            outage.end();
            await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
            assertThat(attempts(payment)).containsExactly("1 delivered 200");
        } finally {
            outage.end();
        }
    }

    @Test
    void countsAFaultAppliedAtCreationOnceCommitted() {
        double before = faultsCounted("delay");
        chaos.add(Fault.DELAY, FaultParameters.delay(Duration.ofMillis(1)), new Match(null, "chaosCount2"), 1);

        pay("chaosCount2");

        assertThat(faultsCounted("delay")).isEqualTo(before + 1);
    }

    @Test
    void countsEachFaultApplied() {
        double before = faultsCounted("lost_response");
        chaos.add(Fault.LOST_RESPONSE, FaultParameters.NONE, new Match(null, "chaosCount1"), 2);
        Payment payment = pay("chaosCount1");

        await().atMost(WAIT).until(() -> delivery(payment).status() == DeliveryStatus.DELIVERED);
        assertThat(faultsCounted("lost_response")).isEqualTo(before + 2);
    }

    /** The payments in the order the core first heard of them. */
    private static List<Payment> receivedOrder(String transactionId, List<Payment> payments) {
        return CORE.receivedFor(transactionId).stream()
                .map(request -> payments.stream()
                        .filter(payment ->
                                request.body().contains(payment.endToEndId().value()))
                        .findFirst()
                        .orElseThrow())
                .distinct()
                .toList();
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
