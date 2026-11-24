package dev.axion.core.webhooks.web;

import dev.axion.core.webhooks.ReplayResult;
import dev.axion.core.webhooks.WebhooksApi;
import org.springframework.http.ResponseEntity;

/** The answer to a replay, alike on {@code /v1} and {@code /internal}. */
final class WebhookReplays {

    private WebhookReplays() {}

    // 202: the replay is queued, and its request follows in the delivery's log.
    static ResponseEntity<WebhookDeliveryResponse> answer(ReplayResult result) {
        return switch (result) {
            case ReplayResult.Queued queued ->
                ResponseEntity.accepted().body(WebhookDeliveryResponse.of(queued.delivery()));
            case ReplayResult.DeliveryNotFound unknown -> throw WebhookProblems.notFound();
            case ReplayResult.EndpointDisabled disabled -> throw WebhookProblems.endpointDisabled();
            case ReplayResult.LimitReached limit ->
                throw WebhookProblems.replayLimitReached(WebhooksApi.MAX_REPLAYS, limit.retryAt());
        };
    }
}
