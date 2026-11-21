package dev.finaxis.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.finaxis.simulator.PostgresTestConfiguration;
import dev.finaxis.simulator.delivery.StubCore.Received;
import dev.finaxis.simulator.delivery.StubCore.Reply;
import dev.finaxis.simulator.spi.Debtor;
import dev.finaxis.simulator.spi.Payment;
import dev.finaxis.simulator.spi.PaymentOrder;
import dev.finaxis.simulator.spi.Spi;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The dispatcher against a stub core over HTTP, with a real database (rails simulator design, section 6). */
@SpringBootTest(properties = "simulator.dispatcher.enabled=true")
@Import(PostgresTestConfiguration.class)
class DispatcherTest {

    private static final StubCore CORE = new StubCore();
    private static final Duration WAIT = Duration.ofSeconds(10);

    private final Spi spi;
    private final JdbcClient jdbc;

    @Autowired
    DispatcherTest(Spi spi, JdbcClient jdbc) {
        this.spi = spi;
        this.jdbc = jdbc;
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
    void deliversTheNotificationOfAPayment() {
        Payment payment = pay("dispatch1");

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        Received request = CORE.receivedFor("dispatch1").getFirst();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/rails/pix/credits");
        assertThat(request.headers()).containsEntry("content-type", "application/json");
        assertThat(request.headers()).containsEntry("x-correlation-id", messageId(payment));
        assertThat(request.body()).isEqualTo(message(payment));
        assertThat(attempts(payment)).containsExactly("1 delivered 200");
    }

    @Test
    void sendsADeliveredNotificationOnlyOnce() {
        Payment payment = pay("dispatch2");

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        await().during(Duration.ofSeconds(1))
                .atMost(WAIT)
                .until(() -> CORE.receivedFor("dispatch2").size() == 1);
    }

    @Test
    void stopsAtARefusal() {
        CORE.reply("dispatch3", Reply.status(401));
        Payment payment = pay("dispatch3");

        await().atMost(WAIT).until(() -> status(payment).equals("refused"));
        assertThat(attempts(payment)).containsExactly("1 refused 401");
        assertThat(nextAttempt(payment)).isNull();
    }

    @Test
    void keepsANotificationTheCoreDidNotAcceptPending() {
        CORE.reply("dispatch4", Reply.status(503));
        Payment payment = pay("dispatch4");

        await().atMost(WAIT).until(() -> attempts(payment).size() == 1);
        assertThat(attempts(payment)).containsExactly("1 retried 503");
        assertThat(status(payment)).isEqualTo("pending");
        assertThat(nextAttempt(payment)).isNotNull();
    }

    @Test
    void retriesWhenTheCoreDoesNotAnswerInTime() {
        CORE.reply("dispatch5", new Reply(200, Map.of(), Duration.ofSeconds(6)));
        Payment payment = pay("dispatch5");

        await().atMost(WAIT).until(() -> attempts(payment).size() == 1);
        assertThat(attempts(payment)).containsExactly("1 retried timeout");
    }

    @Test
    void sendsADeliveryLeftPendingByAnEarlierProcess() {
        Payment payment = pay("dispatch6");
        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        var leftOver = UUID.randomUUID();
        var earlier = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        jdbc.sql("""
                        INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, next_attempt_at, created_at)
                        SELECT ?, end_to_end_id, message_id, 'redelivery', message, 'pending', ?, ?
                        FROM deliveries WHERE end_to_end_id = ?
                        """)
                .params(leftOver, earlier, earlier, payment.endToEndId().value())
                .update();

        await().atMost(WAIT).until(() -> CORE.receivedFor("dispatch6").size() == 2);
        List<Received> requests = CORE.receivedFor("dispatch6");
        assertThat(requests.get(1).body()).isEqualTo(requests.get(0).body());
    }

    private Payment pay(String transactionId) {
        return spi.settle(
                new PaymentOrder(10_000, transactionId, null, "000201", new Debtor("Ana Souza", "52601815906"), null));
    }

    private String status(Payment payment) {
        return jdbc.sql("SELECT status FROM deliveries WHERE end_to_end_id = ? AND kind = 'original'")
                .param(payment.endToEndId().value())
                .query(String.class)
                .single();
    }

    private @Nullable OffsetDateTime nextAttempt(Payment payment) {
        return jdbc.sql("SELECT next_attempt_at FROM deliveries WHERE end_to_end_id = ? AND kind = 'original'")
                .param(payment.endToEndId().value())
                .query(OffsetDateTime.class)
                .optional()
                .orElse(null);
    }

    private List<String> attempts(Payment payment) {
        return jdbc.sql("""
                        SELECT a.number || ' ' || a.outcome || ' ' || coalesce(a.response_status::text, a.error)
                        FROM delivery_attempts a JOIN deliveries d ON d.id = a.delivery_id
                        WHERE d.end_to_end_id = ? AND d.kind = 'original'
                        ORDER BY a.number
                        """)
                .param(payment.endToEndId().value())
                .query((row, number) -> row.getString(1))
                .list();
    }

    private String messageId(Payment payment) {
        return jdbc.sql("SELECT message_id FROM deliveries WHERE end_to_end_id = ? AND kind = 'original'")
                .param(payment.endToEndId().value())
                .query(String.class)
                .single();
    }

    private String message(Payment payment) {
        byte[] message = jdbc.sql("SELECT message FROM deliveries WHERE end_to_end_id = ? AND kind = 'original'")
                .param(payment.endToEndId().value())
                .query(byte[].class)
                .single();
        return new String(message, StandardCharsets.UTF_8);
    }
}
