package dev.axion.core.webhooks;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.OpenApiContract;
import dev.axion.core.PostgresTestConfiguration;
import dev.axion.core.events.EventCatalog;
import dev.axion.core.webhooks.domain.EventTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The event types an endpoint may receive (events and webhooks design, section 3.3): every public
 * type the public reference sends to webhooks, as the endpoint's schema lists them, and none the
 * console alone receives.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class EndpointEventTypesTest {

    @Test
    void areTheTypesThePublicReferenceSends() {
        assertThat(EventTypes.WEBHOOK)
                .containsExactlyInAnyOrderElementsOf(OpenApiContract.v1().events());
    }

    @Test
    void arePublicTypesOfTheCatalog(@Autowired EventCatalog catalog) {
        assertThat(catalog.publicTypes()).containsAll(EventTypes.WEBHOOK);
    }

    @Test
    void areTheTypesTheContractLists() throws Exception {
        JsonNode schema = new YAMLMapper()
                .readTree(Files.readString(Path.of("openapi/v1.yaml")))
                .at("/components/schemas/WebhookEventType/enum");
        List<String> listed = new ArrayList<>();
        schema.forEach(type -> listed.add(type.asString()));

        assertThat(listed).containsExactlyInAnyOrderElementsOf(withAll(EventTypes.WEBHOOK));
    }

    private static List<String> withAll(Set<String> types) {
        List<String> all = new ArrayList<>(types);
        all.add(EventTypes.ALL);
        return all;
    }
}
