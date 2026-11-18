package dev.finaxis.core.web;

import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
