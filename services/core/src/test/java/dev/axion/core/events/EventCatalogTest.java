package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.billing.representation.ChargeDetailResponse;
import dev.axion.core.billing.representation.PaymentResponse;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link EventCatalog} (events and webhooks design, sections 3.3 and 5.1), with the core's own JSON
 * mapper, so a resource in an event is compared with the resource as the API writes it.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EventCatalogTest {

    private final EventCatalog catalog;
    private final JsonMapper json;

    @Autowired
    EventCatalogTest(EventCatalog catalog, JsonMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Test
    void writesTheEnvelopeOfACloudEvent() {
        ChargePaid paid = EventFixtures.one(ChargePaid.class);

        PublicEvent event = catalog.render(paid).orElseThrow();
        JsonNode body = json.readTree(event.body());

        assertThat(event.id()).isEqualTo(paid.id());
        assertThat(event.type()).isEqualTo("charge.paid");
        assertThat(event.occurredAt()).isEqualTo(paid.occurredAt());
        assertThat(body.path("specversion").asString()).isEqualTo("1.0");
        assertThat(body.path("id").asString()).isEqualTo(paid.id().publicId().toString());
        assertThat(body.path("source").asString()).isEqualTo("urn:axion:test");
        assertThat(body.path("type").asString()).isEqualTo("charge.paid");
        assertThat(body.path("subject").asString())
                .isEqualTo(paid.charge().charge().id().publicId().toString());
        assertThat(body.path("datacontenttype").asString()).isEqualTo("application/json");
        assertThat(body.path("dataschema").asString())
                .isEqualTo("https://github.com/renatomf/axion/blob/main/services/core/openapi/v1.yaml#charge.paid.v1");
    }

    @Test
    void writesTheTimeInUtcWithMilliseconds() {
        // The fixtures' events occurred at 14:03:11.482913.
        PublicEvent event = catalog.render(EventFixtures.one(ChargePaid.class)).orElseThrow();

        assertThat(json.readTree(event.body()).path("time").asString()).isEqualTo("2026-11-23T14:03:11.482Z");
    }

    @Test
    void namesTheLiveEnvironmentAsItsSource() {
        ChargeCreated test = EventFixtures.one(ChargeCreated.class);
        EventHeader header = test.header();
        var live = new ChargeCreated(
                new EventHeader(
                        header.id(),
                        header.tenant(),
                        Environment.LIVE,
                        header.actor(),
                        header.correlationId(),
                        header.occurredAt()),
                test.charge());

        assertThat(json.readTree(catalog.render(live).orElseThrow().body())
                        .path("source")
                        .asString())
                .isEqualTo("urn:axion:live");
    }

    @Test
    void carriesTheChargeAsTheApiReturnsIt() {
        ChargePaid paid = EventFixtures.one(ChargePaid.class);

        JsonNode data = json.readTree(catalog.render(paid).orElseThrow().body()).path("data");

        assertThat(data.propertyNames()).containsExactly("object");
        assertThat(data.path("object")).isEqualTo(written(ChargeDetailResponse.of(paid.charge())));
    }

    @Test
    void carriesAFlaggedPaymentWithItsCharge() {
        PaymentFlagged flagged = EventFixtures.one(PaymentFlagged.class);

        JsonNode body = json.readTree(catalog.render(flagged).orElseThrow().body());

        assertThat(body.path("subject").asString())
                .isEqualTo(flagged.payment().id().publicId().toString());
        assertThat(body.path("data").path("object")).isEqualTo(written(PaymentResponse.of(flagged.payment())));
        assertThat(body.path("data").path("charge")).isEqualTo(written(ChargeDetailResponse.of(flagged.charge())));
    }

    @Test
    void namesTheRefundAmongTheRefundedChargesRefunds() {
        ChargeRefunded refunded = EventFixtures.one(ChargeRefunded.class);

        JsonNode body = json.readTree(catalog.render(refunded).orElseThrow().body());

        assertThat(body.path("subject").asString())
                .isEqualTo(refunded.charge().charge().id().publicId().toString());
        assertThat(body.path("data").path("object")).isEqualTo(written(ChargeDetailResponse.of(refunded.charge())));
        assertThat(body.path("data").path("refund").asString())
                .isEqualTo(refunded.refund().id().publicId().toString());
    }

    @ParameterizedTest
    @MethodSource("dev.axion.core.events.EventFixtures#catalog")
    void rendersExactlyTheEventsWithAPublicType(DomainEvent event) {
        assertThat(catalog.render(event).map(PublicEvent::type)).isEqualTo(publicType(event));
    }

    @Test
    void listsEveryPublicType() {
        assertThat(catalog.publicTypes())
                .containsExactlyInAnyOrder(
                        "charge.created", "charge.paid", "charge.expired", "payment.flagged", "charge.refunded");
    }

    @Test
    void rendersTheSameBytesEveryTime() {
        ChargeRefunded refunded = EventFixtures.one(ChargeRefunded.class);

        assertThat(catalog.render(refunded).orElseThrow().bodyBytes())
                .isEqualTo(catalog.render(refunded).orElseThrow().bodyBytes());
    }

    // Written and read back, as the event's body is, so numbers compare as the same node type.
    private JsonNode written(Object representation) {
        return json.readTree(json.writeValueAsString(representation));
    }

    // The table of section 3.3: security events never leave the core.
    private static Optional<String> publicType(DomainEvent event) {
        return switch (event.getClass().getSimpleName()) {
            case "ChargeCreated" -> Optional.of("charge.created");
            case "ChargePaid" -> Optional.of("charge.paid");
            case "ChargeExpired" -> Optional.of("charge.expired");
            case "PaymentFlagged" -> Optional.of("payment.flagged");
            case "ChargeRefunded" -> Optional.of("charge.refunded");
            default -> Optional.empty();
        };
    }
}
