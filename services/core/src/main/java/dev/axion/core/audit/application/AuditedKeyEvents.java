package dev.axion.core.audit.application;

import dev.axion.core.access.ApiKeyIssued;
import dev.axion.core.access.ApiKeyRevoked;
import dev.axion.core.access.ApiKeyRolled;
import dev.axion.core.access.ApiKeyView;
import dev.axion.core.tenancy.Permission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Records who gave access (events and webhooks design, section 9.4): every key issued, rolled or
 * revoked, with its name and scopes, never its secret.
 *
 * <p>Each listener runs without a transaction of its own: {@link AuditTrail} binds the event's tenant
 * before it begins one.
 */
@Component
class AuditedKeyEvents {

    private static final String RESOURCE = "api_key";

    private final AuditTrail trail;

    AuditedKeyEvents(AuditTrail trail) {
        this.trail = trail;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ApiKeyIssued event) {
        trail.append(event, issued(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ApiKeyRolled event) {
        trail.append(event, rolled(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ApiKeyRevoked event) {
        trail.append(event, revoked(event));
    }

    static AuditedAction issued(ApiKeyIssued event) {
        return action("api_key.issued", event.key(), Map.of());
    }

    /** Recorded on the key rolled, which keeps working until its grace period ends. */
    static AuditedAction rolled(ApiKeyRolled event) {
        ApiKeyView rolled = event.rolled();
        Map<String, Object> roll = new HashMap<>();
        roll.put("replacement", event.replacement().id().publicId().toString());
        rolled.expiresAt().ifPresent(end -> roll.put("expires_at", end.toString()));
        return action("api_key.rolled", rolled, roll);
    }

    static AuditedAction revoked(ApiKeyRevoked event) {
        return action("api_key.revoked", event.key(), Map.of());
    }

    private static AuditedAction action(String action, ApiKeyView key, Map<String, Object> more) {
        Map<String, Object> details = new HashMap<>(more);
        details.put("name", key.name());
        details.put("scopes", scopes(key));
        return new AuditedAction(action, RESOURCE, key.id().publicId().toString(), details);
    }

    // Sorted, so the same scopes always give the same canonical text.
    private static List<String> scopes(ApiKeyView key) {
        return key.scopes().stream().map(Permission::code).sorted().toList();
    }
}
