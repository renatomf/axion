package dev.axion.core.rails;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The rails' contract, {@code rails.yaml}, which belongs to the rails simulator (rails simulator
 * design, section 5.1). The core's tests read it from there, so the messages they send are ones the
 * rails may send.
 */
final class RailsContract {

    private static final Path FILE =
            Path.of("../rails-simulator/openapi/rails.yaml").toAbsolutePath().normalize();
    private static final String IRI = FILE.toUri().toString();

    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            registry -> registry.schemas(iri -> iri.startsWith(IRI) ? json() : null));

    private RailsContract() {}

    /** Asserts that the body is a credit notification the contract allows. */
    static void assertIsACreditNotification(String body) {
        assertThat(SCHEMAS.getSchema(SchemaLocation.of(IRI + "#/components/schemas/CreditNotification"))
                        .validate(body, InputFormat.JSON))
                .as("the body is not a credit notification of rails.yaml")
                .isEmpty();
    }

    private static String json() {
        try {
            return new YAMLMapper().readTree(Files.readString(FILE)).toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
