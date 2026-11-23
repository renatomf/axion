package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import dev.axion.core.web.CodePointLength;
import dev.axion.core.webhooks.EndpointChanges;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

// A JSON merge patch (RFC 7396): a field left out keeps its value, and null removes it. Only the
// description can be removed, so a null for another field is a 422 naming it. A class, not a
// record: Jackson calls a setter only for a field the body has, which tells a description left out
// from a null one. The limits are CreateWebhookEndpointRequest's.
final class UpdateWebhookEndpointRequest {

    @CodePointLength(max = WebhooksApi.MAX_URL_LENGTH)
    private @Nullable String url;

    @CodePointLength(min = 1, max = WebhooksApi.MAX_DESCRIPTION_LENGTH)
    private @Nullable String description;

    private boolean descriptionSent;

    @Size(min = 1, max = WebhooksApi.MAX_EVENT_TYPES)
    @WildcardAlone
    private @Nullable List<@NotNull @WebhookEventType String> enabledEvents;

    @Pattern(regexp = "enabled|disabled", message = "must be enabled or disabled")
    private @Nullable String status;

    @JsonSetter(value = "url", nulls = Nulls.FAIL)
    void setUrl(String url) {
        this.url = url;
    }

    @JsonSetter("description")
    void setDescription(@Nullable String description) {
        this.description = description;
        this.descriptionSent = true;
    }

    @JsonSetter(value = "enabled_events", nulls = Nulls.FAIL)
    void setEnabledEvents(List<String> enabledEvents) {
        this.enabledEvents = enabledEvents;
    }

    @JsonSetter(value = "status", nulls = Nulls.FAIL)
    void setStatus(String status) {
        this.status = status;
    }

    EndpointChanges toEndpointChanges() {
        return new EndpointChanges(
                Optional.ofNullable(url),
                descriptionSent ? Optional.of(Optional.ofNullable(description)) : Optional.empty(),
                Optional.ofNullable(enabledEvents).map(Set::copyOf),
                Optional.ofNullable(status).map(value -> EndpointStatus.valueOf(value.toUpperCase(Locale.ROOT))));
    }
}
