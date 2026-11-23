package dev.axion.core.webhooks.web;

import dev.axion.core.access.RequiresPermission;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.web.ReplayBody;
import dev.axion.core.webhooks.CreateEndpointResult;
import dev.axion.core.webhooks.RollSecretResult;
import dev.axion.core.webhooks.UpdateEndpointResult;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.servlet.http.HttpServletRequest;
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

@RestController
class WebhookEndpointsController {

    private static final String PATH = "/v1/webhook_endpoints";

    private static final String MERGE_PATCH = "application/merge-patch+json";

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
            case CreateEndpointResult.InvalidUrl invalid -> throw WebhookProblems.invalidUrl(invalid.reason());
            case CreateEndpointResult.LimitReached reached -> throw WebhookProblems.limitExceeded(reached.limit());
        };
    }

    // A malformed starting_after is a 422 naming it.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH)
    WebhookEndpointListResponse list(
            @RequestParam(name = "limit", defaultValue = "10") @Min(1) @Max(WebhooksApi.MAX_PAGE_SIZE) int limit,
            @RequestParam(name = "starting_after", required = false) @Pattern(regexp = EndpointIds.PATTERN) @Nullable
                    String startingAfter) {
        return WebhookEndpointListResponse.of(webhooks.endpoints(EndpointIds.cursor(startingAfter), limit));
    }

    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @GetMapping(PATH + "/{id}")
    WebhookEndpointResponse endpoint(@PathVariable("id") String id) {
        return webhooks.endpoint(EndpointIds.inPath(id))
                .map(WebhookEndpointResponse::of)
                .orElseThrow(WebhookProblems::notFound);
    }

    // A JSON merge patch, under its own media type or application/json, which most clients send.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @PatchMapping(
            path = PATH + "/{id}",
            consumes = {MERGE_PATCH, MediaType.APPLICATION_JSON_VALUE})
    WebhookEndpointResponse update(
            @PathVariable("id") String id, @Valid @RequestBody UpdateWebhookEndpointRequest request) {
        return switch (webhooks.update(EndpointIds.inPath(id), request.toEndpointChanges())) {
            case UpdateEndpointResult.Updated updated -> WebhookEndpointResponse.of(updated.endpoint());
            case UpdateEndpointResult.EndpointNotFound unknown -> throw WebhookProblems.notFound();
            case UpdateEndpointResult.InvalidUrl invalid -> throw WebhookProblems.invalidUrl(invalid.reason());
        };
    }

    // 204: the endpoint is gone, so there is nothing to show. Deleting it again is 404, like an
    // unknown endpoint; the request changes nothing more, as DELETE must not.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @DeleteMapping(PATH + "/{id}")
    ResponseEntity<Void> delete(@PathVariable("id") String id) {
        if (webhooks.delete(EndpointIds.inPath(id)).isEmpty()) {
            throw WebhookProblems.notFound();
        }
        return ResponseEntity.noContent().build();
    }

    // Idempotent, and stored without the new secret, like a registration. A retry under another key
    // finds the grace period it began and answers 409 instead of rolling again.
    @RequiresPermission(Permission.WEBHOOKS_MANAGE)
    @PostMapping(PATH + "/{id}/secret/roll")
    RolledWebhookEndpointResponse rollSecret(
            @PathVariable("id") String id,
            @Valid @RequestBody(required = false) @Nullable RollWebhookSecretRequest request,
            HttpServletRequest servletRequest) {
        Duration grace = request == null ? WebhooksApi.DEFAULT_SECRET_GRACE : request.gracePeriod();
        return switch (webhooks.rollSecret(EndpointIds.inPath(id), grace)) {
            case RollSecretResult.Rolled rolled -> {
                RolledWebhookEndpointResponse endpoint = RolledWebhookEndpointResponse.of(rolled.secret());
                ReplayBody.set(servletRequest, endpoint.withoutSecret());
                yield endpoint;
            }
            case RollSecretResult.EndpointNotFound unknown -> throw WebhookProblems.notFound();
            case RollSecretResult.GracePeriodActive active ->
                throw WebhookProblems.gracePeriodActive(active.graceEndsAt());
        };
    }
}
