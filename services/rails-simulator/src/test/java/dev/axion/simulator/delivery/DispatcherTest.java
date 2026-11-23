package dev.axion.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import dev.axion.simulator.PostgresTestConfiguration;
import dev.axion.simulator.TestSigningKey;
import dev.axion.simulator.delivery.StubCore.Received;
import dev.axion.simulator.delivery.StubCore.Reply;
import dev.axion.simulator.spi.Debtor;
import dev.axion.simulator.spi.Payment;
import dev.axion.simulator.spi.PaymentOrder;
import dev.axion.simulator.spi.Spi;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
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
    private final MeterRegistry meters;

    @Autowired
    DispatcherTest(Spi spi, JdbcClient jdbc, MeterRegistry meters) {
        this.spi = spi;
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
    void signsEachRequestWithTheSimulatorsKey() throws GeneralSecurityException {
        Payment payment = pay("dispatch10");

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        Received request = CORE.receivedFor("dispatch10").getFirst();
        String digest = MessageSigner.contentDigest(request.body().getBytes(StandardCharsets.UTF_8));
        assertThat(request.headers()).containsEntry("content-digest", digest);
        String input = request.header("signature-input");
        assertThat(input).contains(";keyid=\"" + TestSigningKey.KEY_ID + "\";alg=\"ed25519\"");
        long created = Long.parseLong(input.replaceAll(".*;created=([0-9]+);.*", "$1"));
        assertThat(created).isCloseTo(Clock.systemUTC().instant().getEpochSecond(), within(60L));
        String base = MessageSigner.signatureBase(
                List.of(
                        Map.entry("@method", request.method()),
                        Map.entry("@path", request.path()),
                        Map.entry("content-type", request.header("content-type")),
                        Map.entry("content-digest", digest)),
                input.substring("rails=".length()));
        String signature = request.header("signature");
        byte[] bytes = Base64.getDecoder().decode(signature.substring("rails=:".length(), signature.length() - 1));
        assertThat(MessageSignerTest.verify(TestSigningKey.publicKey(), base, bytes))
                .isTrue();
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
        double refusedBefore = attemptsCounted("refused");
        Payment payment = pay("dispatch3");

        await().atMost(WAIT).until(() -> status(payment).equals("refused"));
        assertThat(attempts(payment)).containsExactly("1 refused 401");
        assertThat(nextAttempt(payment)).isNull();
        assertThat(attemptsCounted("refused")).isEqualTo(refusedBefore + 1);
    }

    @Test
    void retriesWithBackoffUntilTheCoreAccepts() {
        CORE.reply("dispatch7", Reply.status(503), Reply.status(500));
        Payment payment = pay("dispatch7");

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        assertThat(attempts(payment)).containsExactly("1 retried 503", "2 retried 500", "3 delivered 200");
        List<Received> requests = CORE.receivedFor("dispatch7");
        assertThat(requests).extracting(Received::body).containsOnly(message(payment));
        requests.forEach(RailsContract::assertConforms);
    }

    @Test
    void sendsWhatTheContractDescribesWithEveryOptionalField() {
        Payment payment = spi.settle(new PaymentOrder(
                12_345,
                "dispatch11",
                "123e4567-e12b-12d1-a456-426655440000",
                "000201",
                new Debtor("Bruno Lima", "11144477735"),
                "Mensalidade de novembro"));

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        RailsContract.assertConforms(CORE.receivedFor("dispatch11").getFirst());
    }

    @Test
    void waitsAsLongAsTheCoresRetryAfter() {
        CORE.reply("dispatch8", new Reply(429, Map.of("Retry-After", "120"), Duration.ZERO));
        Payment payment = pay("dispatch8");

        await().atMost(WAIT).until(() -> attempts(payment).size() == 1);
        assertThat(jdbc.sql("""
                        SELECT d.next_attempt_at - a.attempted_at >= interval '120 seconds'
                        FROM deliveries d JOIN delivery_attempts a ON a.delivery_id = d.id
                        WHERE d.end_to_end_id = ? AND d.kind = 'original'
                        """)
                        .param(payment.endToEndId().value())
                        .query(Boolean.class)
                        .single())
                .isTrue();
    }

    @Test
    void failsADeliveryNotAcceptedWithin24Hours() {
        Payment payment = pay("dispatch9");
        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        CORE.reply("dispatch9", Reply.status(503));
        var late = UUID.randomUUID();
        var created = OffsetDateTime.now(ZoneOffset.UTC).minusHours(25);
        jdbc.sql("""
                        INSERT INTO deliveries (id, end_to_end_id, message_id, kind, message, status, next_attempt_at, created_at)
                        SELECT ?, end_to_end_id, message_id, 'redelivery', message, 'pending', ?, ?
                        FROM deliveries WHERE end_to_end_id = ?
                        """)
                .params(late, created, created, payment.endToEndId().value())
                .update();

        await().atMost(WAIT)
                .until(() -> jdbc.sql("SELECT status FROM deliveries WHERE id = ?")
                        .param(late)
                        .query(String.class)
                        .single()
                        .equals("failed"));
        assertThat(jdbc.sql("SELECT outcome || ' ' || response_status FROM delivery_attempts WHERE delivery_id = ?")
                        .param(late)
                        .query(String.class)
                        .single())
                .isEqualTo("failed 503");
    }

    @Test
    void retriesWhenTheCoreDoesNotAnswerInTime() {
        CORE.reply("dispatch5", new Reply(200, Map.of(), Duration.ofSeconds(6)));
        Payment payment = pay("dispatch5");

        await().atMost(WAIT).until(() -> status(payment).equals("delivered"));
        assertThat(attempts(payment)).containsExactly("1 retried timeout", "2 delivered 200");
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

    private double attemptsCounted(String outcome) {
        return meters.counter(Dispatcher.ATTEMPTS, "outcome", outcome).count();
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
