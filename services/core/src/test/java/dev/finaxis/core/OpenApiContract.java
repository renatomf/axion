package dev.finaxis.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Checks that a response of the core is one its OpenAPI contract allows: contract tests (engineering
 * standards, "API"). The contracts in {@code openapi/} are the source of truth, and the console's
 * client is generated from them, so a response that drifts from its contract breaks the console
 * even when every other test passes.
 *
 * <p>For the request's method and path, the contract must declare an operation, a response for the
 * status, and that response's media type; the body is then validated against the response's schema
 * with JSON Schema 2020-12, the schema dialect of OpenAPI 3.1. {@code $ref}s are followed, across
 * files too, such as {@code components.yaml#/responses/Unauthorized}.
 *
 * <p>The schemas are checked strictly: an object may carry only the properties its schema lists,
 * as if every object schema said {@code additionalProperties: false}. The contract itself stays open
 * to new properties, which is how an API grows without breaking clients; the test makes sure that
 * whatever the core sends was documented first.
 */
public final class OpenApiContract {

    private static final Path DIRECTORY = Path.of("openapi").toAbsolutePath().normalize();

    private static final YAMLMapper YAML = new YAMLMapper();

    /** The documents of {@link #DIRECTORY}, read once and made strict, by file name. */
    private static final Map<String, JsonNode> DOCUMENTS = new ConcurrentHashMap<>();

    /**
     * Resolves schemas from the strict documents instead of the files on disk. The registry asks
     * for a document by its IRI, such as {@code file:///…/openapi/components.yaml}.
     */
    private static final SchemaRegistry SCHEMAS = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            registry -> registry.schemas(iri -> iri.startsWith(DIRECTORY.toUri().toString())
                    ? document(Path.of(URI.create(iri)).getFileName().toString())
                            .toString()
                    : null));

    private final String file;

    private OpenApiContract(String file) {
        this.file = file;
    }

    /** The contract of {@code /internal}, {@code openapi/internal.yaml}. */
    public static OpenApiContract internal() {
        return new OpenApiContract("internal.yaml");
    }

    /** The contract of {@code /v1}, {@code openapi/v1.yaml}. */
    public static OpenApiContract v1() {
        return new OpenApiContract("v1.yaml");
    }

    /**
     * Fails unless the contract documents the request's operation and the response's status and
     * media type, and the body matches the documented schema.
     */
    public void assertConforms(MvcTestResult result) {
        MvcResult mvcResult = result.getMvcResult();
        // A MockMvc request always has a method and a URI; the servlet API only allows them to be null.
        String method =
                Objects.requireNonNull(mvcResult.getRequest().getMethod()).toLowerCase(Locale.ROOT);
        String path = Objects.requireNonNull(mvcResult.getRequest().getRequestURI());
        MockHttpServletResponse response = mvcResult.getResponse();
        String operation = method.toUpperCase(Locale.ROOT) + " " + path;

        Located paths = new Located(file, "/paths", document(file).path("paths"));
        Optional<String> template = template(paths.node(), path);
        if (template.isEmpty()) {
            throw new AssertionError(operation + ": the path is not in " + file);
        }
        Located pathItem = paths.child(template.get());
        Located responses = pathItem.require(method, operation).require("responses", operation);
        Located documented = responses
                .require(String.valueOf(response.getStatus()), operation)
                .followRef();

        if (!documented.node().has("content")) {
            assertThat(response.getContentLength())
                    .as("%s: %s documents no body", operation, response.getStatus())
                    .isZero();
            return;
        }
        String contentType = Objects.requireNonNull(
                response.getContentType(),
                () -> operation + ": a " + response.getStatus() + " response must have a content type");
        // "application/json;charset=UTF-8" becomes "application/json", the key the contract uses.
        MediaType parsed = MediaType.parseMediaType(contentType);
        String mediaType = parsed.getType() + "/" + parsed.getSubtype();
        Located schema = documented
                .require("content", operation)
                .require(mediaType, operation)
                .require("schema", operation);

        List<Error> errors = SCHEMAS.getSchema(SchemaLocation.of(schema.iri()))
                .validate(contentAsString(response), InputFormat.JSON);
        assertThat(errors)
                .as("%s: the %s body does not match %s", operation, response.getStatus(), schema.iri())
                .isEmpty();
    }

    /**
     * The template in {@code paths} that a concrete path matches: {@code /internal/tenants/{tenant_id}}
     * matches {@code /internal/tenants/ten_…}. A parameter stands for exactly one path segment.
     */
    private static Optional<String> template(JsonNode paths, String path) {
        return paths.propertyNames().stream()
                .filter(template -> path.matches(template.replaceAll("\\{[^/]+}", "[^/]+")))
                .findFirst();
    }

    private static String contentAsString(MockHttpServletResponse response) {
        try {
            return response.getContentAsString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JsonNode document(String fileName) {
        return DOCUMENTS.computeIfAbsent(fileName, name -> {
            try {
                return strict(YAML.readTree(Files.readString(DIRECTORY.resolve(name))));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /**
     * Closes every object schema that lists its properties and says nothing about others. A node is
     * taken for an object schema when its {@code properties} is an object and its {@code type} is
     * {@code object}, so an OpenAPI field that happens to be called {@code properties} is left alone.
     */
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

    /**
     * A node of a contract document, with where it is: its file and its JSON Pointer, from which
     * the schema registry's IRI is built.
     */
    private record Located(String file, String pointer, JsonNode node) {

        Located child(String name) {
            // JSON Pointer escapes "~" as "~0" and "/" as "~1": /paths/~1internal~1me.
            String escaped = name.replace("~", "~0").replace("/", "~1");
            return new Located(file, pointer + "/" + escaped, node.path(name));
        }

        Located require(String name, String operation) {
            if (!node.has(name)) {
                throw new AssertionError(operation + ": " + file + " has no " + name + " at " + pointer);
            }
            return child(name);
        }

        /** The node a {@code $ref} points to, such as {@code components.yaml#/responses/Unauthorized}. */
        Located followRef() {
            if (!node.has("$ref")) {
                return this;
            }
            String ref = node.path("$ref").asString();
            String[] parts = ref.split("#", 2);
            String target = parts[0].isEmpty() ? file : parts[0];
            String targetPointer = parts.length > 1 ? parts[1] : "";
            return new Located(target, targetPointer, document(target).at(targetPointer)).followRef();
        }

        String iri() {
            return DIRECTORY.resolve(file).toUri() + "#" + pointer;
        }
    }
}
