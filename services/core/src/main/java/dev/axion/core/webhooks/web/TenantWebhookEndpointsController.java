package dev.axion.core.webhooks.web;

import dev.axion.core.access.MemberCaller;
import dev.axion.core.access.RequiresPermission;
import dev.axion.core.access.RequiresStepUp;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.RollSecretResult;
import dev.axion.core.webhooks.UpdateEndpointResult;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    // No idempotency key, as on the rest of /internal: a retry registers a second endpoint.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @RequiresStepUp
    @PostMapping(PATH)
    ResponseEntity<CreatedWebhookEndpointResponse> create(
            MemberCaller caller, @Valid @RequestBody CreateWebhookEndpointRequest request) {
        return switch (webhooks.create(request.toEndpointRequest())) {
            case CreateEndpointResult.Created created -> {
                CreatedWebhookEndpointResponse endpoint = CreatedWebhookEndpointResponse.of(created.endpoint());
                URI location = URI.create(
                        "/internal/tenants/" + caller.tenant().publicId() + "/webhook_endpoints/" + endpoint.id());
                yield ResponseEntity.created(location).body(endpoint);
            }
            case CreateEndpointResult.InvalidUrl invalid -> throw WebhookProblems.invalidUrl(invalid.reason());
            case CreateEndpointResult.LimitReached reached -> throw WebhookProblems.limitExceeded(reached.limit());
        };
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

    // A JSON merge patch, under its own media type or application/json, as on /v1.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @RequiresStepUp
    @PatchMapping(
            path = PATH + "/{endpoint_id}",
            consumes = {WebhookEndpointsController.MERGE_PATCH, MediaType.APPLICATION_JSON_VALUE})
    WebhookEndpointResponse update(
            @PathVariable("endpoint_id") String id, @Valid @RequestBody UpdateWebhookEndpointRequest request) {
        return switch (webhooks.update(EndpointIds.inPath(id), request.toEndpointChanges())) {
            case UpdateEndpointResult.Updated updated -> WebhookEndpointResponse.of(updated.endpoint());
            case UpdateEndpointResult.EndpointNotFound unknown -> throw WebhookProblems.notFound();
            case UpdateEndpointResult.InvalidUrl invalid -> throw WebhookProblems.invalidUrl(invalid.reason());
        };
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @RequiresStepUp
    @DeleteMapping(PATH + "/{endpoint_id}")
    ResponseEntity<Void> delete(@PathVariable("endpoint_id") String id) {
        if (webhooks.delete(EndpointIds.inPath(id)).isEmpty()) {
            throw WebhookProblems.notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // No idempotency key: a retry finds the grace period the first roll began and answers 409.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @RequiresStepUp
    @PostMapping(PATH + "/{endpoint_id}/secret/roll")
    RolledWebhookEndpointResponse rollSecret(
            @PathVariable("endpoint_id") String id,
            @Valid @RequestBody(required = false) @Nullable RollWebhookSecretRequest request) {
        Duration grace = request == null ? WebhooksApi.DEFAULT_SECRET_GRACE : request.gracePeriod();
        return switch (webhooks.rollSecret(EndpointIds.inPath(id), grace)) {
            case RollSecretResult.Rolled rolled -> RolledWebhookEndpointResponse.of(rolled.secret());
            case RollSecretResult.EndpointNotFound unknown -> throw WebhookProblems.notFound();
            case RollSecretResult.GracePeriodActive active ->
                throw WebhookProblems.gracePeriodActive(active.graceEndsAt());
        };
    }
}
