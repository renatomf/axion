package dev.finaxis.core.access.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * The errors of tenant resolution and permission checks (tenancy design, section 4.4), as
 * exceptions the {@code web} module's error handler turns into Problem Details with their {@code
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

    private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return new ErrorResponseException(status, problem, null);
    }
}
