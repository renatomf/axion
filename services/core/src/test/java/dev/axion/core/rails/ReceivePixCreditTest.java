package dev.axion.core.rails;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.DatabaseOwner;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.rails.application.CreditsReceived;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /rails/pix/credits} on the running server, with messages signed as the rails sign
 * them (billing design, sections 7.1 and 7.2). Each test pays with an {@code EndToEndId} of its
 * own, so the committed credits of one never meet another's.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class ReceivePixCreditTest {

    private static final String PATH = "/rails/pix/credits";

    private static final RailsSigner RAILS = new RailsSigner("rails-2026-11");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    @DynamicPropertySource
    static void signingKey(DynamicPropertyRegistry registry) {
        registry.add("finaxis.rails.signing.keys[0].id", RAILS::keyId);
        registry.add("finaxis.rails.signing.keys[0].public-key", RAILS::publicKey);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Clock clock;

    @Autowired
    private DatabaseOwner owner;

    @Autowired
    private MeterRegistry meters;

    private RailsClient rails;

    private final String endToEndId = "E99990011202611211430" + random(11);

    @BeforeEach
    void connect() {
        rails = new RailsClient(port, RAILS);
    }

    @Test
    void sendsACreditNotificationOfTheRailsContract() {
        RailsContract.assertIsACreditNotification(notification().toString());
    }

    @Test
    void recordsACreditAndAcceptsIt() {
        byte[] body = bytes(notification());

        HttpResponse<String> response = rails.post(PATH, body, clock.instant());

        assertThat(response.statusCode()).isEqualTo(202);
        assertThat(response.body()).isEmpty();
        Map<String, @Nullable Object> credit = recorded().getFirst();
        assertThat(credit)
                .containsEntry("message_id", "M99990011aB3dE5gH7jK2mN4pQ6rS8tU")
                .containsEntry("transaction_id", "fx7Kq2mP9wR4tY6uA8sD3fG5h")
                .containsEntry("amount_minor", 10_000L)
                .containsEntry("currency", "BRL")
                .containsEntry("debtor_ispb", "99990011")
                .containsEntry("key_id", "rails-2026-11")
                .containsEntry("status", "received")
                .containsEntry("attempts", 0);
        assertThat(credit.get("content_digest")).isEqualTo(sha256(body));
        assertThat(credit.get("accepted_at")).isEqualTo(Timestamp.from(Instant.parse("2026-11-21T14:30:12.345Z")));
        assertThat(credit.get("next_attempt_at")).isEqualTo(credit.get("received_at"));
        assertThat(credit.get("processed_at")).isNull();
    }

    // Every copy of a message has the same bytes; only its signature differs.
    @Test
    void recordsADuplicatedNotificationOnce() {
        byte[] body = bytes(notification());
        double news = received("new");
        double duplicates = received("duplicate");

        assertThat(rails.post(PATH, body, clock.instant()).statusCode()).isEqualTo(202);
        assertThat(rails.post(PATH, body, clock.instant().plusSeconds(1)).statusCode())
                .isEqualTo(202);
        assertThat(recorded()).hasSize(1);
        assertThat(received("new")).isEqualTo(news + 1);
        assertThat(received("duplicate")).isEqualTo(duplicates + 1);
    }

    @Test
    void ignoresAFieldTheCoreDoesNotKnow() {
        ObjectNode notification = notification();
        notification.put("purpose", "OTHR");

        assertThat(rails.post(PATH, bytes(notification), clock.instant()).statusCode())
                .isEqualTo(202);
        assertThat(recorded()).hasSize(1);
    }

    @Test
    void recordsWhoPaidOnlyByTheirInstitution() {
        rails.post(PATH, bytes(notification()), clock.instant());

        assertThat(recorded().getFirst().values().stream().map(String::valueOf))
                .noneMatch(value -> value.contains("Ana Souza") || value.contains("52601815906"));
    }

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(
            nullValues = "null",
            value = {
                "/messageType, pacs.009",
                "/messageType, null",
                "/localInstrument, MANU",
                "/creditorAgent/ispb, 99990002",
                "/creditorAgent/ispb, null",
                "/messageId, M9999001aB3dE5gH7jK2mN4pQ6rS8tU",
                "/endToEndId, E9999001120261121143aB3dE5gH7jK",
                "/endToEndId, null",
                "/transactionId, fx7Kq2mP9wR4tY6uA8sD3fG5h0",
                "/transactionId, fx7Kq2mP9wR4tY6uA8sD3fG5-",
                "/debtorAgent/ispb, 9999001",
                "/interbankSettlementAmount/currency, USD",
                "/interbankSettlementAmount/amount, 100.0",
                "/interbankSettlementAmount/amount, 100",
                "/interbankSettlementAmount/amount, -1.00",
                "/interbankSettlementAmount/amount, 0.00",
                "/interbankSettlementAmount/amount, 1000000000000000.00",
                "/acceptanceDateTime, 2026-11-21T14:30:12Z",
                "/acceptanceDateTime, 2026-11-31T14:30:12.345Z",
            })
    void refusesAMalformedNotificationAndRecordsNothing(String pointer, @Nullable String value) {
        ObjectNode notification = notification();
        set(notification, pointer, value);
        double rejected = received("rejected");

        HttpResponse<String> response = rails.post(PATH, bytes(notification), clock.instant());

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"detail\":\"The credit notification is malformed.\"");
        assertThat(recorded()).isEmpty();
        assertThat(received("rejected")).isEqualTo(rejected + 1);
    }

    // Amounts are decimal strings, never JSON numbers, so no binary fraction rounds a centavo.
    @Test
    void refusesAnAmountSentAsANumber() {
        ObjectNode notification = notification();
        ((ObjectNode) notification.get("interbankSettlementAmount")).put("amount", 100.00);

        assertThat(rails.post(PATH, bytes(notification), clock.instant()).statusCode())
                .isEqualTo(400);
        assertThat(recorded()).isEmpty();
    }

    @Test
    void refusesABodyThatIsNotJson() {
        assertThat(rails.post(PATH, "pacs.008".getBytes(StandardCharsets.UTF_8), clock.instant())
                        .statusCode())
                .isEqualTo(400);
    }

    @Test
    void refusesAnotherContentType() {
        byte[] body = bytes(notification());
        var headers = RAILS.sign("POST", PATH, "text/plain", body, clock.instant());

        assertThat(rails.send("POST", PATH, "text/plain", body, headers).statusCode())
                .isEqualTo(415);
        assertThat(recorded()).isEmpty();
    }

    @Test
    void refusesAStaleNotificationAndRecordsNothing() {
        double rejected = received("rejected");

        HttpResponse<String> response =
                rails.post(PATH, bytes(notification()), clock.instant().minus(Duration.ofMinutes(6)));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(recorded()).isEmpty();
        assertThat(received("rejected")).isEqualTo(rejected + 1);
    }

    /** The credit notification of the rails simulator design, section 5.2, for this test's payment. */
    private ObjectNode notification() {
        var notification = (ObjectNode) JSON.readTree("""
                {
                  "messageId": "M99990011aB3dE5gH7jK2mN4pQ6rS8tU",
                  "messageType": "pacs.008",
                  "creationDateTime": "2026-11-21T14:30:12.401Z",
                  "endToEndId": "%s",
                  "transactionId": "fx7Kq2mP9wR4tY6uA8sD3fG5h",
                  "localInstrument": "QRDN",
                  "interbankSettlementAmount": { "amount": "100.00", "currency": "BRL" },
                  "acceptanceDateTime": "2026-11-21T14:30:12.345Z",
                  "debtor": { "name": "Ana Souza", "taxId": "52601815906" },
                  "debtorAccount": { "branch": "0001", "number": "123456", "type": "CACC" },
                  "debtorAgent": { "ispb": "99990011" },
                  "creditorAgent": { "ispb": "99990001" },
                  "creditorAccount": { "proxy": "a2b3bccd-2305-4632-9494-a795efa6fbb1" },
                  "remittanceInformation": "Mensalidade de novembro"
                }
                """.formatted(endToEndId));
        return notification;
    }

    private List<Map<String, @Nullable Object>> recorded() {
        return owner.jdbc()
                .sql("SELECT * FROM rails.pix_credits WHERE end_to_end_id = ?")
                .param(endToEndId)
                .query()
                .listOfRows();
    }

    private double received(String result) {
        return meters.get(CreditsReceived.METRIC)
                .tag("result", result)
                .counter()
                .count();
    }

    /** Sets the field at the pointer, or removes it for {@code null}. */
    private static void set(ObjectNode root, String pointer, @Nullable String value) {
        int last = pointer.lastIndexOf('/');
        JsonNode parent = last == 0 ? root : root.at(pointer.substring(0, last));
        String field = pointer.substring(last + 1);
        if (value == null) {
            ((ObjectNode) parent).remove(field);
        } else {
            ((ObjectNode) parent).put(field, value);
        }
    }

    private static byte[] bytes(JsonNode notification) {
        return notification.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] body) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(body);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(int length) {
        var text = new StringBuilder();
        for (int i = 0; i < length; i++) {
            text.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return text.toString();
    }
}
