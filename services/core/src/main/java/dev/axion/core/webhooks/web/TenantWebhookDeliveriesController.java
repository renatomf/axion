package dev.axion.core.webhooks.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * An endpoint's delivery log for the console (events and webhooks design, section 12.2), as {@code
 * /v1} shows it, and the replay of its deliveries. The member's tenant is the one in the path,
 * already checked and bound. No step-up: reading changes nothing, and a replay sends again only
 * what the endpoint was already sent, at most {@value WebhooksApi#MAX_REPLAYS} times a day.
 */
@RestController
class TenantWebhookDeliveriesController {

    private static final String PATH = "/internal/tenants/{tenant_id}/webhook_endpoints/{endpoint_id}/deliveries";

    private final WebhooksApi webhooks;

    TenantWebhookDeliveriesController(WebhooksApi webhooks) {
        this.webhooks = webhooks;
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH)
    WebhookDeliveryListResponse list(
            @PathVariable("endpoint_id") String id,
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(WebhooksApi.MAX_PAGE_SIZE) int limit,
            @RequestParam(name = "starting_after", required = false) @Pattern(regexp = DeliveryQuery.ID) @Nullable
                    String startingAfter,
            @RequestParam(name = "status", required = false) @Pattern(regexp = DeliveryQuery.STATUS) @Nullable
                    String status,
            @RequestParam(name = "type", required = false) @Pattern(regexp = DeliveryQuery.EVENT_TYPE) @Nullable
                    String type) {
        return webhooks.deliveries(
                        EndpointIds.inPath(id),
                        DeliveryQuery.filter(status, type),
                        DeliveryQuery.cursor(startingAfter),
                        limit)
                .map(WebhookDeliveryListResponse::of)
                .orElseThrow(WebhookProblems::notFound);
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH + "/{delivery_id}")
    WebhookDeliveryDetailResponse delivery(
            @PathVariable("endpoint_id") String id, @PathVariable("delivery_id") String deliveryId) {
        return webhooks.delivery(EndpointIds.inPath(id), DeliveryQuery.inPath(deliveryId))
                .map(WebhookDeliveryDetailResponse::of)
                .orElseThrow(WebhookProblems::notFound);
    }

    // No idempotency key: a retry queues another replay, which the daily limit bounds.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @PostMapping(PATH + "/{delivery_id}/replay")
    ResponseEntity<WebhookDeliveryResponse> replay(
            @PathVariable("endpoint_id") String id, @PathVariable("delivery_id") String deliveryId) {
        return WebhookReplays.answer(webhooks.replay(EndpointIds.inPath(id), DeliveryQuery.inPath(deliveryId)));
    }
}
