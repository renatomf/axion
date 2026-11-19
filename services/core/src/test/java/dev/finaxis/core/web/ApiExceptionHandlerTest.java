package dev.finaxis.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How errors are returned over HTTP.
 *
 * <p>{@code @WebMvcTest} starts only the Spring MVC layer: controllers, controller advice and
 * filters, without a database, so no Docker is needed. {@code controllers} limits the controllers to
 * {@link FailingController}, so the application's own controllers, and the services they need, stay
 * out. It exists only in this test and is imported explicitly, because Spring Boot never scans
 * classes nested in a test class.
 *
 * <p>{@code addFilters = false} leaves the servlet filters, Spring Security among them, out of
 * {@link MockMvcTester}: these tests are about errors raised inside Spring MVC, which security would
 * otherwise reject before they happen. Security has its own tests.
 */
@WebMvcTest(controllers = ApiExceptionHandlerTest.FailingController.class)
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
    }
}
