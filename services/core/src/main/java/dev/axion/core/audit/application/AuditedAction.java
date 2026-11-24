package dev.axion.core.audit.application;

import java.util.Map;

/**
 * What a domain event records in the audit trail (events and webhooks design, section 9.1), besides
 * the actor, time and correlation ID its header carries.
 *
 * @param action such as {@code api_key.issued} or {@code charge.refunded}
 * @param details the facts of the action, such as a key's name and scopes or a refund's amount;
 *     never a secret and never personal data
 */
record AuditedAction(String action, String resourceType, String resourceId, Map<String, ?> details) {

    AuditedAction {
        details = Map.copyOf(details);
    }
}
