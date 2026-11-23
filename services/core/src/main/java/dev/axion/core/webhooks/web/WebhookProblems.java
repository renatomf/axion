package dev.axion.core.webhooks.web;

import dev.axion.core.webhooks.domain.UrlRefusal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The errors of the webhook endpoint operations, alike on {@code /v1} and {@code /internal}. */
final class WebhookProblems {

    private WebhookProblems() {}

    // Thrown, not returned, so the idempotency step stores a registration's refusal with the key and
    // a retry gets it again. The reason names the rule, never the addresses the name resolved to
    // (events and webhooks design, section 7.1).
    static ErrorResponseException invalidUrl(UrlRefusal reason) {
        String detail = switch (reason) {
            case MALFORMED -> "must be an absolute URL with a host, and no user information or fragment";
            case MUST_BE_HTTPS -> "must be https";
            case MUST_USE_DOMAIN_NAME -> "must name its host by a domain name, not an IP address";
            case PORT_NOT_ALLOWED -> "must use port 443 or a port from 1024 to 65535";
            case MUST_BE_PUBLIC -> "must name a host on the public internet";
            case MUST_RESOLVE -> "must name a host that resolves";
        };
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "The request has invalid fields.");
        problem.setProperty("code", "validation_failed");
        problem.setProperty(
                "errors",
                List.of(Map.of(
                        "field",
                        "url",
                        "detail",
                        detail,
                        "reason",
                        reason.name().toLowerCase(Locale.ROOT))));
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    // 422, not 409: no other request conflicts, and a retry changes nothing until an endpoint goes.
    static ErrorResponseException limitExceeded(int limit) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "The tenant already has " + limit + " webhook endpoints.");
        problem.setProperty("code", "limit_exceeded");
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    static ErrorResponseException gracePeriodActive(Instant graceEndsAt) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT,
                "The previous secret signs until " + graceEndsAt + "; the secret rolls again from then.");
        problem.setProperty("code", "grace_period_active");
        return new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
    }

    static ErrorResponseException notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource was not found.");
        problem.setProperty("code", "not_found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
