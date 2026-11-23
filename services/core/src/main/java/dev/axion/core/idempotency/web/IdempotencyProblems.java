package dev.axion.core.idempotency.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The idempotency errors of the idempotency design, section 3.4, as Problem Details. A key is text
 * the client chose, so no detail repeats it.
 */
final class IdempotencyProblems {

    private IdempotencyProblems() {}

    static ErrorResponseException keyRequired() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "idempotency_key_required",
                "A POST to the public API requires an Idempotency-Key header.");
    }

    static ErrorResponseException invalidKey() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid_idempotency_key",
                "An Idempotency-Key is 1 to 255 visible ASCII characters.");
    }

    static ErrorResponseException contentTooLarge(int limit) {
        return problem(
                HttpStatus.CONTENT_TOO_LARGE,
                "content_too_large",
                "A request body is at most " + limit / 1024 + " KiB.");
    }

    static ErrorResponseException requestInProgress() {
        ErrorResponseException problem = problem(
                HttpStatus.CONFLICT,
                "request_in_progress",
                "A request with this Idempotency-Key is still running; retry later.");
        problem.getHeaders().set(HttpHeaders.RETRY_AFTER, "1");
        return problem;
    }

    static ErrorResponseException keyReused() {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency_key_reused",
                "This Idempotency-Key was used for a request with another path or body.");
    }

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
