package dev.finaxis.core.idempotency.web;

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

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
