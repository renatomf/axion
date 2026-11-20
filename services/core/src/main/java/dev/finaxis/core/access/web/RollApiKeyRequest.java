package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.AccessApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

// 604_800 seconds is AccessApi.MAX_ROLL_GRACE: an annotation needs a constant.
record RollApiKeyRequest(
        @JsonProperty("grace_period_seconds") @Min(0) @Max(604_800) @Nullable
        Integer gracePeriodSeconds) {

    Duration gracePeriod() {
        return gracePeriodSeconds == null ? AccessApi.DEFAULT_ROLL_GRACE : Duration.ofSeconds(gracePeriodSeconds);
    }
}
