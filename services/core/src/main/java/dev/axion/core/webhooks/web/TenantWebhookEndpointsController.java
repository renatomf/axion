package dev.axion.core.webhooks.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The webhook endpoint operations of {@code /v1}, for the console (events and webhooks design,
 * section 12.2). The member's tenant is the one in the path, already checked and bound.
 */
@RestController
class TenantWebhookEndpointsController {

    private static final String PATH = "/internal/tenants/{tenant_id}/webhook_endpoints";

    private final WebhooksApi webhooks;

    TenantWebhookEndpointsController(WebhooksApi webhooks) {
        this.webhooks = webhooks;
    }

    // No step-up: reading shows no secret and changes nothing.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH)
    WebhookEndpointListResponse list(
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(WebhooksApi.MAX_PAGE_SIZE) int limit,
            @RequestParam(name = "starting_after", required = false) @Pattern(regexp = EndpointIds.PATTERN) @Nullable
                    String startingAfter) {
        return WebhookEndpointListResponse.of(webhooks.endpoints(EndpointIds.cursor(startingAfter), limit));
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH + "/{endpoint_id}")
    WebhookEndpointResponse endpoint(@PathVariable("endpoint_id") String id) {
        return webhooks.endpoint(EndpointIds.inPath(id))
                .map(WebhookEndpointResponse::of)
                .orElseThrow(WebhookProblems::notFound);
    }
}
