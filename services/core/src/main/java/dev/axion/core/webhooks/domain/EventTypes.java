package dev.axion.core.webhooks.domain;

import java.util.Set;

/**
 * The public event types an endpoint may receive (events and webhooks design, section 3.3): those
 * the catalog sends to webhooks. Security events and {@code webhook_endpoint.disabled} never are.
 */
public final class EventTypes {

    /** Every type, including those added later; alone, never beside a type. */
    public static final String ALL = "*";

    public static final Set<String> WEBHOOK =
            Set.of("charge.created", "charge.paid", "charge.expired", "payment.flagged", "charge.refunded");

    private EventTypes() {}

    /** {@link #ALL} alone, or types of {@link #WEBHOOK}. */
    public static boolean isValidSelection(Set<String> types) {
        return types.equals(Set.of(ALL)) || (!types.isEmpty() && WEBHOOK.containsAll(types));
    }
}
