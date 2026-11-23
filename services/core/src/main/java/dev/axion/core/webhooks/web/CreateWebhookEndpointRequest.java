package dev.axion.core.webhooks.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.web.CodePointLength;
import dev.axion.core.webhooks.EndpointRequest;
import dev.axion.core.webhooks.WebhooksApi;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

// The limits of EndpointRequest, checked here so that a breach is a 422 naming the field. The URL's
// rules that need its name resolved are the module's; a refusal is a 422 on url too. No tenant
// field: the tenant is the API key's.
record CreateWebhookEndpointRequest(
        @NotNull @CodePointLength(max = WebhooksApi.MAX_URL_LENGTH)
        String url,

        @CodePointLength(min = 1, max = WebhooksApi.MAX_DESCRIPTION_LENGTH) @Nullable
        String description,

        @JsonProperty("enabled_events") @NotNull @Size(min = 1, max = WebhooksApi.MAX_EVENT_TYPES) @WildcardAlone
        List<@NotNull @WebhookEventType String> enabledEvents) {

    EndpointRequest toEndpointRequest() {
        return new EndpointRequest(url, Optional.ofNullable(description), Set.copyOf(enabledEvents));
    }
}
