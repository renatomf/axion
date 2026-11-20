package dev.finaxis.core.access.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The errors of tenant resolution, permission checks (tenancy design, section 4.4) and the API key
 * operations, as exceptions the {@code web} module's error handler turns into Problem Details with their {@code
 * code}. The details are generic, like those of authentication failures, so a response never says
 * more than its status and code.
 */
final class AccessProblems {

    private AccessProblems() {}

    /** A tenant that does not exist, or that the caller does not belong to: the two look alike. */
    static ErrorResponseException notFound() {
        return problem(HttpStatus.NOT_FOUND, "not_found", "The resource was not found.");
    }

    /** A tenant the caller belongs to that is suspended or closed. */
    static ErrorResponseException tenantInactive() {
        return problem(HttpStatus.FORBIDDEN, "tenant_inactive", "The tenant is not active.");
    }

    /** A caller without the permission the operation requires, or an operation that declares none. */
    static ErrorResponseException insufficientPermission() {
        return problem(HttpStatus.FORBIDDEN, "insufficient_permission", "Access is denied.");
    }

    /**
     * A member who asked for a key with scopes they do not hold. The code is that of a missing
     * permission, since that is what it is; the detail says which rule applies, not which scopes,
     * which the member can see in their own roles.
     */
    static ErrorResponseException scopesNotHeld() {
        return problem(
                HttpStatus.FORBIDDEN,
                "insufficient_permission",
                "A key's scopes must be permissions the caller holds.");
    }

    /**
     * A tenant that already has the most working keys allowed. {@code 422} rather than {@code 409}:
     * the request conflicts with no other, and retrying it changes nothing until a key is revoked
     * (overview design, section 7.6).
     */
    static ErrorResponseException limitExceeded(int limit) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "limit_exceeded",
                "The tenant already has " + limit + " working API keys; revoke one first.");
    }

    /**
     * A key that was revoked or already rolled, so it cannot be rolled. {@code 409}: the request
     * conflicts with the key's current state, and only rolling its replacement can succeed.
     */
    static ErrorResponseException keyNotRollable() {
        return problem(HttpStatus.CONFLICT, "key_not_rollable", "The key was revoked or already rolled.");
    }

    /**
     * A member who must authenticate again before the operation. The {@code WWW-Authenticate}
     * challenge of RFC 9470 tells the console how recent the authentication must be; the console
     * then asks Keycloak for a login with that {@code max_age}.
     */
    static ErrorResponseException stepUpRequired() {
        ErrorResponseException problem =
                problem(HttpStatus.FORBIDDEN, "step_up_required", "A recent authentication is required.");
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
