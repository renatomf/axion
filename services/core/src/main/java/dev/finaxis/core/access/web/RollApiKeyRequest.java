package dev.finaxis.core.access.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.access.AccessApi;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * A {@code RollApiKeyRequest} of {@code openapi/internal.yaml}: how to roll a key. The body is
 * optional, and so is its one field; without them, the old key keeps working for {@link
 * AccessApi#DEFAULT_ROLL_GRACE}.
 *
 * <p>{@code @Min} and {@code @Max} let a {@code null} pass, as every standard constraint does, so a
 * missing grace period is not an error. The bounds must be constants for the annotations, so the
 * seven days of {@link AccessApi#MAX_ROLL_GRACE} are written out in seconds; the controller tests
 * check both edges.
 *
 * @param gracePeriodSeconds how long the old key keeps working, or {@code null} for the default
 */
record RollApiKeyRequest(
        @JsonProperty("grace_period_seconds") @Min(0) @Max(604_800) @Nullable
        Integer gracePeriodSeconds) {

    /** The grace period the request names, or the default when it names none. */
    Duration gracePeriod() {
        return gracePeriodSeconds == null ? AccessApi.DEFAULT_ROLL_GRACE : Duration.ofSeconds(gracePeriodSeconds);
    }
}
