package dev.axion.core.notifications.domain;

import dev.axion.core.tenancy.Permission;
import java.util.Map;
import java.util.Set;

/**
 * The permission a member needs to receive each public type on the live stream (events and webhooks
 * design, section 10). A type it does not list reaches no one, so a new type stays off the stream
 * until its permission is chosen.
 */
public final class StreamPermissions {

    private static final Map<String, Permission> REQUIRED = Map.of(
            "charge.created", Permission.CHARGES_READ,
            "charge.paid", Permission.CHARGES_READ,
            "charge.expired", Permission.CHARGES_READ,
            "payment.flagged", Permission.CHARGES_READ,
            "charge.refunded", Permission.CHARGES_READ,
            "webhook_endpoint.disabled", Permission.WEBHOOKS_MANAGE);

    private StreamPermissions() {}

    public static boolean allows(Set<Permission> permissions, String type) {
        Permission required = REQUIRED.get(type);
        return required != null && permissions.contains(required);
    }

    /** Every type the stream may carry. */
    public static Set<String> types() {
        return REQUIRED.keySet();
    }
}
