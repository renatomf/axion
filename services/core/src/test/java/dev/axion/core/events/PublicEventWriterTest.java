package dev.axion.core.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.axion.core.shared.Actor;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.PublicId;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A {@link PublicEventWriter} contributed to the catalog (events and webhooks design, section 5.1). */
class PublicEventWriterTest {

    private static final PublicId SUBJECT = new PublicId("probe", UUID.randomUUID());

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void writesTheEventInTheCatalogsEnvelope() {
        var catalog = new CloudEventCatalog(json, List.of(new ProbeWriter("probe.done")));
        var event = new ProbeDone(header(), "first");

        PublicEvent rendered = catalog.render(event).orElseThrow();
        JsonNode body = json.readTree(rendered.body());

        assertThat(rendered.type()).isEqualTo("probe.done");
        assertThat(body.path("specversion").asString()).isEqualTo("1.0");
        assertThat(body.path("id").asString()).isEqualTo(event.id().publicId().toString());
        assertThat(body.path("type").asString()).isEqualTo("probe.done");
        assertThat(body.path("subject").asString()).isEqualTo(SUBJECT.toString());
        assertThat(body.path("data").path("name").asString()).isEqualTo("first");
    }

    @Test
    void namesTheSchemaInTheWritersContract() {
        var catalog = new CloudEventCatalog(json, List.of(new ProbeWriter("probe.done")));

        JsonNode body = json.readTree(
                catalog.render(new ProbeDone(header(), "first")).orElseThrow().body());

        assertThat(body.path("dataschema").asString())
                .isEqualTo(
                        "https://github.com/renatomf/axion/blob/main/services/core/openapi/internal.yaml#probe.done.v1");
    }

    @Test
    void listsTheWritersTypeAmongThePublicTypes() {
        var catalog = new CloudEventCatalog(json, List.of(new ProbeWriter("probe.done")));

        assertThat(catalog.publicTypes()).contains("probe.done", "charge.paid");
    }

    @Test
    void refusesASecondWriterOfAnEvent() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CloudEventCatalog(
                        json, List.of(new ProbeWriter("probe.done"), new ProbeWriter("probe.finished"))));
    }

    @Test
    void refusesAWriterOfABillingType() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new CloudEventCatalog(json, List.of(new ProbeWriter("charge.paid"))));
    }

    private static EventHeader header() {
        return new EventHeader(
                new EventId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Environment.TEST,
                new Actor.Job("probe"),
                "correlation-1",
                Instant.parse("2026-11-24T10:00:00Z"));
    }

    private record ProbeDone(EventHeader header, String name) implements DomainEvent {}

    private record ProbeData(String name) {}

    private record ProbeWriter(String type) implements PublicEventWriter<ProbeDone> {

        @Override
        public Class<ProbeDone> event() {
            return ProbeDone.class;
        }

        @Override
        public EventContract contract() {
            return EventContract.INTERNAL;
        }

        @Override
        public PublicId subject(ProbeDone event) {
            return SUBJECT;
        }

        @Override
        public Object data(ProbeDone event) {
            return new ProbeData(event.name());
        }
    }
}
