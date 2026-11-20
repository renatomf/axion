package dev.finaxis.core.web;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every exception raised while handling a request into an RFC 9457 Problem Details response
 * ({@code application/problem+json}).
 *
 * <p>{@code @RestControllerAdvice} makes Spring MVC call this bean's exception handlers for every
 * controller. {@link ResponseEntityExceptionHandler} already handles the exceptions Spring MVC itself
 * raises, such as an unknown path ({@code 404}) or an unsupported HTTP method ({@code 405}), and
 * returns them as a {@code ProblemDetail}. Spring Boot backs off its own equivalent handler when this
 * bean exists, so there is exactly one place that decides what an error looks like.
 *
 * <p>Every problem carries a {@value #CODE} property: a stable, snake_case identifier that clients
 * can branch on, unlike {@code title} and {@code detail}, which are for humans and may change. A
 * handler may set a specific code (such as {@code idempotency_key_reused}); otherwise the code is
 * the HTTP status name, such as {@code not_found}.
 *
 * <p>An exception no other handler knows is a bug or an infrastructure failure. The client gets a
 * {@code 500} with a generic detail; the exception, whose message and stack trace may reveal
 * internals, is only logged, with the request's correlation ID.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";

    /** The code of a request whose fields fail validation; its problem lists them in {@code errors}. */
    static final String VALIDATION_FAILED = "validation_failed";

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Handles every exception not handled more specifically: Spring MVC picks the handler whose
     * exception type is the closest match, so this one only gets what no other handler declares.
     */
    @ExceptionHandler(Exception.class)
    public @Nullable ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        // Nothing from the request is logged: the path is chosen by the caller and could forge log
        // lines. The correlation ID on the log line identifies the request.
        LOG.error("Unexpected error while handling a request", ex);
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "An unexpected error occurred.");
        return handleExceptionInternal(ex, problem, new HttpHeaders(), status, request);
    }

    /**
     * A request body annotated {@code @Valid} whose constraints fail, such as a blank name under
     * {@code @NotBlank}. Spring MVC checks the constraints after reading the body and before the
     * controller method runs, and throws {@link MethodArgumentNotValidException}; the method never
     * runs.
     *
     * <p>The answer is {@code 422 validation_failed}, not Spring's default {@code 400}: the body was
     * read, so the request is well formed, but its content is unacceptable (RFC 9110, section
     * 15.5.21). {@code errors} names each failing field and why, sorted so the response does not
     * depend on the order the validator happens to report them in. The rejected value is never
     * echoed: it could be a secret, or text crafted to mislead whoever reads the response.
     */
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        HttpStatus unprocessable = HttpStatus.UNPROCESSABLE_CONTENT;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(unprocessable, "The request has invalid fields.");
        problem.setProperty(CODE, VALIDATION_FAILED);
        problem.setProperty("errors", fieldErrors(ex));
        return handleExceptionInternal(ex, problem, headers, unprocessable, request);
    }

    /**
     * One {@link FieldProblem} per failed constraint. A constraint on the whole object, rather than
     * on one field, is reported under the object's name.
     */
    private static List<FieldProblem> fieldErrors(MethodArgumentNotValidException ex) {
        Stream<FieldProblem> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldProblem(error.getField(), message(error.getDefaultMessage())));
        Stream<FieldProblem> objects = ex.getBindingResult().getGlobalErrors().stream()
                .filter(error -> !(error instanceof FieldError))
                .map(error -> new FieldProblem(error.getObjectName(), message(error.getDefaultMessage())));
        return Stream.concat(fields, objects)
                .sorted(Comparator.comparing(FieldProblem::field).thenComparing(FieldProblem::detail))
                .toList();
    }

    private static String message(@Nullable String message) {
        return message == null ? "is invalid" : message;
    }

    /** A {@code FieldError} of the contract: written by Jackson as {@code {"field": …, "detail": …}}. */
    record FieldProblem(String field, String detail) {}

    /**
     * Every response built by {@link ResponseEntityExceptionHandler} passes through this method, so
     * the default code is added in one place.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && !hasCode(problem)) {
            problem.setProperty(CODE, defaultCode(statusCode));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private static boolean hasCode(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        return properties != null && properties.containsKey(CODE);
    }

    /** {@code 404} becomes {@code not_found}; a status Spring does not know becomes {@code error}. */
    static String defaultCode(HttpStatusCode statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        return status == null ? "error" : status.name().toLowerCase(Locale.ROOT);
    }
}
