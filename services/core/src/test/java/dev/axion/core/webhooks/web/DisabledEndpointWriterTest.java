package dev.axion.core.webhooks.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.events.EventFixtures;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code webhook_endpoint.disabled} as the catalog renders it with {@link DisabledEndpointWriter}
 * (events and webhooks design, sections 3.3 and 8.6), with the core's own JSON mapper.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class DisabledEndpointWriterTest {

    private final EventCatalog catalog;
    private final JsonMapper json;

    @Autowired
    DisabledEndpointWriterTest(EventCatalog catalog, JsonMapper json) {
        this.catalog = catalog;
        this.json = json;
    }

    @Test
    void carriesTheEndpointAsTheApiReturnsItAndWhenItStartedFailing() {
        WebhookEndpointDisabled disabled = EventFixtures.one(WebhookEndpointDisabled.class);

        JsonNode body = json.readTree(catalog.render(disabled).orElseThrow().body());

        assertThat(body.path("type").asString()).isEqualTo("webhook_endpoint.disabled");
        assertThat(body.path("subject").asString())
                .isEqualTo(disabled.endpoint().id().publicId().toString());
        assertThat(body.path("data").propertyNames()).containsExactlyInAnyOrder("object", "failing_since");
        assertThat(body.path("data").path("object"))
                .isEqualTo(written(WebhookEndpointResponse.of(disabled.endpoint())));
        assertThat(body.path("data").path("failing_since").asString())
                .isEqualTo(disabled.failingSince().toString());
    }

    @Test
    void namesItsSchemaInTheInternalContract() {
        JsonNode body = json.readTree(catalog.render(EventFixtures.one(WebhookEndpointDisabled.class))
                .orElseThrow()
                .body());

        assertThat(body.path("dataschema").asString())
                .isEqualTo(
                        "https://github.com/renatomf/axion/blob/main/services/core/openapi/internal.yaml#webhook_endpoint.disabled.v1");
    }

    // Written and read back, as the event's body is, so values compare as the same node type.
    private JsonNode written(Object representation) {
        return json.readTree(json.writeValueAsString(representation));
    }
}
