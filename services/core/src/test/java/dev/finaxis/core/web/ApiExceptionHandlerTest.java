package dev.finaxis.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * How errors are returned over HTTP.
 *
 * <p>{@code @WebMvcTest} starts only the Spring MVC layer: controllers, controller advice and
 * filters, without a database, so no Docker is needed. {@code controllers} limits the controllers to
 * {@link FailingController}, so the application's own controllers, and the services they need, stay
 * out. It exists only in this test and is imported explicitly, because Spring Boot never scans
 * classes nested in a test class.
 *
 * <p>{@code excludeFilters} leaves out the other modules' Spring MVC setup, such as the permission
 * check of {@code access}, which needs services this slice does not start. Spring Boot's own setup
 * comes from auto-configuration, not from scanning, so it stays.
 *
 * <p>{@code addFilters = false} leaves the servlet filters, Spring Security among them, out of
 * {@link MockMvcTester}: these tests are about errors raised inside Spring MVC, which security would
 * otherwise reject before they happen. Security has its own tests.
 */
@WebMvcTest(
        controllers = ApiExceptionHandlerTest.FailingController.class,
        excludeFilters = @Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfigurer.class))
@AutoConfigureMockMvc(addFilters = false)
@Import(ApiExceptionHandlerTest.FailingController.class)
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
                .doesNotContain("at dev.finaxis");
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

    /** Posts the body to {@link FailingController#validated}. */
    private MvcTestResult validated(String body) {
        return mvc.post()
                .uri("/test/validated")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
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

        /** Answers with the name, once Spring MVC has validated the body. */
        @PostMapping("/test/validated")
        String validated(@Valid @RequestBody Form form) {
            return form.name();
        }
    }

    /**
     * A request body with constraints. A missing property arrives as {@code null}, which {@code
     * @NotBlank} and {@code @NotEmpty} reject as well; {@code maxRetries} is optional, and {@code
     * @Min}, like every standard constraint, lets a {@code null} pass.
     */
    record Form(
            @NotBlank @Size(max = 10) String name,
            @NotEmpty List<@NotBlank String> tags,
            @JsonProperty("max_retries") @Min(0) @Nullable Integer maxRetries) {}
}
