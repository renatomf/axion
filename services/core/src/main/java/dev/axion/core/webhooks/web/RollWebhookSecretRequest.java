package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

// 604_800 seconds is WebhooksApi.MAX_SECRET_GRACE: an annotation needs a constant.
record RollWebhookSecretRequest(
        @JsonProperty("grace_period_seconds") @Min(0) @Max(604_800) @Nullable
        Integer gracePeriodSeconds) {

    Duration gracePeriod() {
        return gracePeriodSeconds == null ? WebhooksApi.DEFAULT_SECRET_GRACE : Duration.ofSeconds(gracePeriodSeconds);
    }
}
