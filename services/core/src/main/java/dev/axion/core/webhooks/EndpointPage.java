package dev.axion.core.webhooks;

import java.util.List;
import java.util.Optional;

/** One page of the tenant's endpoints, newest first; {@code next} is empty on the last page. */
public record EndpointPage(List<EndpointView> endpoints, Optional<EndpointCursor> next) {

    public EndpointPage {
        endpoints = List.copyOf(endpoints);
    }

    public boolean hasMore() {
        return next.isPresent();
    }
}
