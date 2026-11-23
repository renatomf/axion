package dev.axion.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan.Filter;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * How errors are returned over HTTP, in the Spring MVC layer only, without a database. Other
 * modules' MVC setup and the servlet filters are left out: these errors arise inside Spring MVC.
 */
@WebMvcTest(
        controllers = ApiExceptionHandlerTest.FailingController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfigurer.class))
@AutoConfigureMockMvc(addFilters = false)
@Import({ApiExceptionHandlerTest.FailingController.class, StrictJsonConfiguration.class})
class ApiExceptionHandlerTest {

    private final MockMvcTester mvc;

    @Autowired
    ApiExceptionHandlerTest(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    /** {@code type} is left out: RFC 9457 reads a missing {@code type} as {@code about:blank}. */
    @Test
    void unknownPathIsANotFoundProblem() {
        assertThat(mvc.get().uri("/no-such-path"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"title": "Not Found", "status": 404, "instance": "/no-such-path", "code": "not_found"}
                        """);
    }

    @Test
    void unsupportedMethodIsAMethodNotAllowedProblem() {
        assertThat(mvc.post().uri("/test/failure"))
                .hasStatus(HttpStatus.METHOD_NOT_ALLOWED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("method_not_allowed");
    }

    @Test
    void unexpectedExceptionIsAGenericInternalErrorProblem() {
        assertThat(mvc.get().uri("/test/failure"))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {"status": 500, "detail": "An unexpected error occurred.", "code": "internal_server_error"}
                        """);
    }

    @Test
    void unexpectedExceptionLeaksNoInternals() {
        assertThat(mvc.get().uri("/test/failure"))
                .bodyText()
                .doesNotContain(FailingController.INTERNAL_DETAIL)
                .doesNotContain("IllegalStateException")
                .doesNotContain("at dev.axion");
    }

    @Test
    void specificCodeIsKept() {
        assertThat(mvc.get().uri("/test/conflict"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("request_in_progress");
    }

    @Test
    void validBodyReachesTheController() {
        assertThat(validated("""
                        {"name": "Acme", "tags": ["a"]}
                        """)).hasStatus(HttpStatus.OK).hasBodyTextEqualTo("Acme");
    }

    @Test
    void invalidFieldsAreAValidationProblemListingEachField() {
        assertThat(validated("""
                        {"name": " ", "tags": ["a", ""]}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "status": 422,
                          "detail": "The request has invalid fields.",
                          "code": "validation_failed",
                          "errors": [
                            {"field": "name", "detail": "must not be blank"},
                            {"field": "tags[1]", "detail": "must not be blank"}
                          ]
                        }
                        """);
    }

    @Test
    void missingFieldsAreAValidationProblem() {
        assertThat(validated("{}"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("name", "tags"));
    }

    @Test
    void invalidFieldIsNamedAsTheRequestSpellsIt() {
        assertThat(validated("""
                        {"name": "Acme", "tags": ["a"], "max_retries": -1}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("max_retries"));
    }

    @Test
    void unknownFieldIsAValidationProblemNamingIt() {
        assertThat(validated("""
                        {"name": "Acme", "tags": ["a"], "max_retry": 3}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "status": 422,
                          "code": "validation_failed",
                          "errors": [{"field": "max_retry", "detail": "is not a known field"}]
                        }
                        """);
    }

    @Test
    void unknownNestedFieldIsNamedWithItsPath() {
        assertThat(validated("""
                        {"name": "Acme", "tags": ["a"], "items": [{"sku": "x"}, {"sku": "y", "qty": 1}]}
                        """))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .isEqualTo(List.of("items[1].qty"));
    }

    @Test
    void javaPathsBecomeSnakeCase() {
        assertThat(ApiExceptionHandler.jsonPath("name")).isEqualTo("name");
        assertThat(ApiExceptionHandler.jsonPath("tags[1]")).isEqualTo("tags[1]");
        assertThat(ApiExceptionHandler.jsonPath("items[0].unitPrice")).isEqualTo("items[0].unit_price");
        assertThat(ApiExceptionHandler.jsonPath("line2Address")).isEqualTo("line2_address");
        assertThat(ApiExceptionHandler.jsonPath("metadata[orderId]")).isEqualTo("metadata[orderId]");
    }

    @Test
    void validationProblemDoesNotEchoTheRejectedValue() {
        String rejected = "do-not-echo-this-value";

        assertThat(validated("""
                        {"name": "%s", "tags": ["a"]}
                        """.formatted(rejected)))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .bodyText()
                .doesNotContain(rejected);
    }

    @Test
    void unreadableBodyIsABadRequestProblem() {
        assertThat(validated("{\"name\": "))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("bad_request");
    }

    @Test
    void valueOfAnotherTypeIsABadRequestProblem() {
        for (String value : new String[] {"2.5", "2.0", "\"3\"", "true"}) {
            assertThat(validated("""
                            {"name": "Acme", "tags": ["a"], "max_retries": %s}
                            """.formatted(value)))
                    .as("max_retries %s", value)
                    .hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson()
                    .extractingPath("$.code")
                    .isEqualTo("bad_request");
        }
        assertThat(validated("""
                        {"name": 5, "tags": ["a"]}
                        """)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    private MvcTestResult validated(String body) {
        return mvc.post()
                .uri("/test/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    @Test
    void validQueryParametersReachTheController() {
        assertThat(mvc.get().uri("/test/parameters?max_items=100&tag[eq]=abc"))
                .hasStatus(HttpStatus.OK)
                .bodyText()
                .isEqualTo("100 abc");
    }

    @Test
    void invalidQueryParametersAreAValidationProblemNamingEach() {
        assertThat(mvc.get().uri("/test/parameters?max_items=101&tag[eq]=abcd"))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .isLenientlyEqualTo("""
                        {
                          "status": 422,
                          "code": "validation_failed",
                          "errors": [
                            {"field": "max_items", "detail": "must be less than or equal to 100"},
                            {"field": "tag[eq]", "detail": "size must be between 0 and 3"}
                          ]
                        }
                        """);
    }

    @Test
    void queryParameterOfAnotherTypeIsABadRequestProblem() {
        assertThat(mvc.get().uri("/test/parameters?max_items=ten"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.code")
                .isEqualTo("bad_request");
    }

    @Test
    void statusSpringDoesNotKnowHasAGenericCode() {
        assertThat(ApiExceptionHandler.defaultCode(HttpStatusCode.valueOf(499))).isEqualTo("error");
    }

    @RestController
    static class FailingController {

        static final String INTERNAL_DETAIL = "connection to db-primary.internal:5432 refused";

        @GetMapping("/test/failure")
        String failure() {
            throw new IllegalStateException(INTERNAL_DETAIL);
        }

        @GetMapping("/test/conflict")
        String conflict() {
            ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
            problem.setProperty(ApiExceptionHandler.CODE, "request_in_progress");
            throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
        }

        /** Answers with the parameters, once Spring MVC has validated them. */
        @GetMapping("/test/parameters")
        String parameters(
                @RequestParam(name = "max_items", defaultValue = "10") @Min(1) @Max(100) int maxItems,
                @RequestParam(name = "tag[eq]", required = false) @Size(max = 3) @Nullable String tag) {
            return maxItems + " " + tag;
        }

        /** Answers with the name, once Spring MVC has validated the body. */
        @PostMapping("/test/validated")
        String validated(@Valid @RequestBody Form form) {
            return form.name();
        }
    }

    record Form(
            @NotBlank @Size(max = 10) String name,
            @NotEmpty List<@NotBlank String> tags,
            @JsonProperty("max_retries") @Min(0) @Nullable Integer maxRetries,
            @Nullable List<Item> items) {}

    record Item(String sku) {}
}
