package dev.axion.core.audit.application;

import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointCreated;
import dev.axion.core.webhooks.WebhookEndpointDeleted;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import dev.axion.core.webhooks.WebhookEndpointUpdated;
import dev.axion.core.webhooks.WebhookSecretRolled;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Records who changed where the money's notifications go (events and webhooks design, section 9.4):
 * every endpoint created, changed, deleted, disabled for failing, or given a new secret. A record
 * holds the endpoint's URL, the event types it receives and its status, never its secret, nor its
 * description, which is the tenant's free text.
 *
 * <p>Each listener runs without a transaction of its own: {@link AuditTrail} binds the event's tenant
 * before it begins one.
 */
@Component
class AuditedEndpointEvents {

    private static final String RESOURCE = "webhook_endpoint";

    private final AuditTrail trail;

    AuditedEndpointEvents(AuditTrail trail) {
        this.trail = trail;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(WebhookEndpointCreated event) {
        trail.append(event, created(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(WebhookEndpointUpdated event) {
        trail.append(event, updated(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(WebhookEndpointDeleted event) {
        trail.append(event, deleted(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(WebhookSecretRolled event) {
        trail.append(event, secretRolled(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(WebhookEndpointDisabled event) {
        trail.append(event, disabled(event));
    }

    static AuditedAction created(WebhookEndpointCreated event) {
        return action("webhook_endpoint.created", event.endpoint(), Map.of());
    }

    /** Recorded with the endpoint before the change, so the trail shows where notifications went. */
    static AuditedAction updated(WebhookEndpointUpdated event) {
        return action("webhook_endpoint.updated", event.endpoint(), Map.of("previous", endpoint(event.previous())));
    }

    static AuditedAction deleted(WebhookEndpointDeleted event) {
        return action("webhook_endpoint.deleted", event.endpoint(), Map.of());
    }

    static AuditedAction secretRolled(WebhookSecretRolled event) {
        return action(
                "webhook_endpoint.secret_rolled",
                event.endpoint(),
                Map.of(
                        "previous_secret_expires_at",
                        event.previousSecretExpiresAt().toString()));
    }

    static AuditedAction disabled(WebhookEndpointDisabled event) {
        return action(
                "webhook_endpoint.disabled",
                event.endpoint(),
                Map.of("failing_since", event.failingSince().toString()));
    }

    private static AuditedAction action(String action, EndpointView endpoint, Map<String, Object> more) {
        Map<String, Object> details = new HashMap<>(more);
        details.putAll(endpoint(endpoint));
        return new AuditedAction(action, RESOURCE, endpoint.id().publicId().toString(), details);
    }

    // The event types sorted, so the same endpoint always gives the same canonical text.
    private static Map<String, Object> endpoint(EndpointView endpoint) {
        Map<String, Object> facts = new HashMap<>();
        facts.put("url", endpoint.url());
        facts.put("enabled_events", endpoint.enabledEvents().stream().sorted().toList());
        facts.put("status", lowercase(endpoint.status()));
        endpoint.disabledReason().ifPresent(reason -> facts.put("disabled_reason", lowercase(reason)));
        return facts;
    }

    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
