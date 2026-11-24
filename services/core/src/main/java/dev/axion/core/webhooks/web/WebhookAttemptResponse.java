package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.webhooks.AttemptView;
import dev.axion.core.webhooks.domain.Attempt;
import java.time.Instant;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

// The excerpt is returned as the string it is; JSON escapes it, and nothing here reads it.
record WebhookAttemptResponse(
        String id,
        String object,
        boolean replay,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("duration_ms") long durationMs,
        @JsonProperty("status_code") @Nullable Integer statusCode,
        @Nullable String failure,
        @JsonProperty("response_excerpt") @Nullable String responseExcerpt) {

    static WebhookAttemptResponse of(AttemptView attempt) {
        Integer statusCode = null;
        String failure = null;
        switch (attempt.outcome()) {
            case Attempt.Answered(int code) -> statusCode = code;
            case Attempt.Failed(var reason) -> failure = reason.name().toLowerCase(Locale.ROOT);
        }
        return new WebhookAttemptResponse(
                attempt.id().publicId().toString(),
                "webhook_attempt",
                attempt.replay(),
                attempt.startedAt(),
                attempt.duration().toMillis(),
                statusCode,
                failure,
                attempt.responseExcerpt().orElse(null));
    }
}
