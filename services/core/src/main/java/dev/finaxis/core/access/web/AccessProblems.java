package dev.finaxis.core.access.web;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The access errors of tenancy design, section 4.4, as Problem Details. Details stay generic: a
 * response says no more than its status and code.
 */
public final class AccessProblems {

    private AccessProblems() {}

    // A tenant that does not exist and one the caller does not belong to look alike.
    static ErrorResponseException notFound() {
        return problem(HttpStatus.NOT_FOUND, "not_found", "The resource was not found.");
    }

    public static ErrorResponseException tenantInactive() {
        return problem(HttpStatus.FORBIDDEN, "tenant_inactive", "The tenant is not active.");
    }

    /** Retry-After is in whole seconds, rounded up so that a retry at that time finds a token. */
    public static ErrorResponseException rateLimited(Duration retryAfter) {
        ErrorResponseException problem =
                problem(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many requests; retry later.");
        long seconds = Math.max(1, retryAfter.plusSeconds(1).minusNanos(1).toSeconds());
        problem.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        return problem;
    }

    static ErrorResponseException insufficientPermission() {
        return problem(HttpStatus.FORBIDDEN, "insufficient_permission", "Access is denied.");
    }

    static ErrorResponseException scopesNotHeld() {
        return problem(
                HttpStatus.FORBIDDEN,
                "insufficient_permission",
                "A key's scopes must be permissions the caller holds.");
    }

    // 422, not 409: no other request conflicts, and a retry changes nothing until a key is revoked.
    static ErrorResponseException limitExceeded(int limit) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "limit_exceeded",
                "The tenant already has " + limit + " working API keys; revoke one first.");
    }

    static ErrorResponseException keyNotRollable() {
        return problem(HttpStatus.CONFLICT, "key_not_rollable", "The key was revoked or already rolled.");
    }

    static ErrorResponseException stepUpRequired() {
        ErrorResponseException problem =
                problem(HttpStatus.FORBIDDEN, "step_up_required", "A recent authentication is required.");
        // The RFC 9470 challenge tells the console how recent the login must be.
        problem.getHeaders()
                .set(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"insufficient_user_authentication\", "
                                + "error_description=\"A recent authentication is required\", "
                                + "max_age=\"" + RecentAuthentication.MAX_AGE.toSeconds() + "\"");
        return problem;
    }

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
