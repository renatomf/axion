package dev.finaxis.simulator.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.finaxis.simulator.delivery.StubCore.Received;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks a request the simulator sent against the credit notification of {@code openapi/rails.yaml}: its
 * body against the message's schema and each required header against its parameter. Strict: an object may
 * carry only the properties its schema lists, so nothing reaches the core that the contract does not
 * document.
 */
final class RailsContract {

    private static final Path FILE =
            Path.of("openapi/rails.yaml").toAbsolutePath().normalize();
    private static final String IRI = FILE.toUri().toString();
    private static final JsonNode DOCUMENT = strict(read());

    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            registry -> registry.schemas(iri -> iri.startsWith(IRI) ? DOCUMENT.toString() : null));

    private RailsContract() {}

    static void assertConforms(Received request) {
        JsonNode operation = DOCUMENT.at("/webhooks/pixCredit/post");
        assertThat(request.method()).isEqualTo("POST");

        List<Error> bodyErrors = validate(
                operation
                        .at("/requestBody/content/application~1json/schema/$ref")
                        .asString(),
                request.body());
        assertThat(bodyErrors)
                .as("the body does not match the credit notification")
                .isEmpty();

        for (JsonNode reference : operation.path("parameters")) {
            String ref = reference.path("$ref").asString();
            JsonNode parameter = DOCUMENT.at(ref.substring(1));
            String name = parameter.path("name").asString();
            String value = request.header(name.toLowerCase(Locale.ROOT));
            List<Error> headerErrors =
                    validate(ref + "/schema", StringNode.valueOf(value).toString());
            assertThat(headerErrors)
                    .as("the %s header does not match the contract", name)
                    .isEmpty();
        }
    }

    private static List<Error> validate(String ref, String json) {
        return SCHEMAS.getSchema(SchemaLocation.of(IRI + ref)).validate(json, InputFormat.JSON);
    }

    private static JsonNode read() {
        try {
            return new YAMLMapper().readTree(Files.readString(FILE));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Only nodes whose type is object, so an OpenAPI field named properties is left alone.
    private static JsonNode strict(JsonNode node) {
        if (node instanceof ObjectNode object
                && "object".equals(object.path("type").asString(""))
                && object.path("properties").isObject()
                && !object.has("additionalProperties")) {
            object.put("additionalProperties", false);
        }
        for (JsonNode child : node) {
            strict(child);
        }
        return node;
    }
}
