package dev.axion.core.webhooks.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.shared.PublicId;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.web.ReplayBody;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.EndpointCursor;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import dev.axion.core.webhooks.domain.UrlRefusal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class WebhookEndpointsController {

    private static final String PATH = "/v1/webhook_endpoints";

    // The contract's pattern for an endpoint's ID.
    private static final String ENDPOINT_ID = "we_[0-7][0-9a-hjkmnp-tv-z]{25}";

    private final WebhooksApi webhooks;

    WebhookEndpointsController(WebhooksApi webhooks) {
        this.webhooks = webhooks;
    }

    // The tenant is the API key's, already bound; the idempotency step runs this inside the key's
    // transaction, which create joins, and stores the response without its secret.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @PostMapping(PATH)
    ResponseEntity<CreatedWebhookEndpointResponse> create(
            @Valid @RequestBody CreateWebhookEndpointRequest request, HttpServletRequest servletRequest) {
        return switch (webhooks.create(request.toEndpointRequest())) {
            case CreateEndpointResult.Created created -> {
                CreatedWebhookEndpointResponse endpoint = CreatedWebhookEndpointResponse.of(created.endpoint());
                ReplayBody.set(servletRequest, endpoint.withoutSecret());
                yield ResponseEntity.created(URI.create(PATH + "/" + endpoint.id()))
                        .body(endpoint);
            }
            case CreateEndpointResult.InvalidUrl invalid -> throw invalidUrl(invalid.reason());
            case CreateEndpointResult.LimitReached reached -> throw limitExceeded(reached.limit());
        };
    }

    // A malformed starting_after is a 422 naming it.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH)
    WebhookEndpointListResponse list(
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(WebhooksApi.MAX_PAGE_SIZE) int limit,
            @RequestParam(name = "starting_after", required = false) @Pattern(regexp = ENDPOINT_ID) @Nullable
                    String startingAfter) {
        var cursor = startingAfter == null
                ? EndpointCursor.first()
                : EndpointCursor.after(new WebhookEndpointId(
                        PublicId.parse(WebhookEndpointId.PREFIX, startingAfter).uuid()));
        return WebhookEndpointListResponse.of(webhooks.endpoints(cursor, limit));
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH + "/{id}")
    WebhookEndpointResponse endpoint(@PathVariable("id") String id) {
        return webhooks.endpoint(endpointIn(id))
                .map(WebhookEndpointResponse::of)
                .orElseThrow(WebhookEndpointsController::notFound);
    }

    // A malformed ID answers 404, like another tenant's endpoint, not 400.
    private static WebhookEndpointId endpointIn(String text) {
        try {
            return new WebhookEndpointId(
                    PublicId.parse(WebhookEndpointId.PREFIX, text).uuid());
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    // Thrown, not returned, so the idempotency step stores the refusal with the key and a retry gets
    // it again. The reason names the rule, never the addresses the name resolved to (events and
    // webhooks design, section 7.1).
    private static ErrorResponseException invalidUrl(UrlRefusal reason) {
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
    private static ErrorResponseException limitExceeded(int limit) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNPROCESSABLE_CONTENT, "The tenant already has " + limit + " webhook endpoints.");
        problem.setProperty("code", "limit_exceeded");
        return new ErrorResponseException(HttpStatus.UNPROCESSABLE_CONTENT, problem, null);
    }

    private static ErrorResponseException notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource was not found.");
        problem.setProperty("code", "not_found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
