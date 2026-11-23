package dev.axion.core.webhooks.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.shared.PublicId;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.EndpointCursor;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    // The tenant is the API key's, already bound. A malformed starting_after is a 422 naming it.
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

    private static ErrorResponseException notFound() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "The resource was not found.");
        problem.setProperty("code", "not_found");
        return new ErrorResponseException(HttpStatus.NOT_FOUND, problem, null);
    }
}
