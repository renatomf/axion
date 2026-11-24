package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.TenantId;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointCreated;
import dev.axion.core.webhooks.WebhookEndpointDeleted;
import dev.axion.core.webhooks.WebhookEndpointDisabled;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhookEndpointUpdated;
import dev.axion.core.webhooks.WebhookSecretRolled;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What each webhook endpoint change records in the audit trail (events and webhooks design, section 9.4). */
class AuditedEndpointEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T15:04:05.123456Z");
    private static final String URL = "https://orders.example.com/axion";
    private static final List<String> EVENTS = List.of("charge.paid", "charge.refunded");

    private final WebhookEndpointId id = new WebhookEndpointId(UUID.randomUUID());
    private final EndpointView enabled = endpoint(URL, EndpointStatus.ENABLED, Optional.empty());

    @Test
    void recordsACreatedEndpointWithoutItsDescription() {
        assertThat(AuditedEndpointEvents.created(new WebhookEndpointCreated(header(), enabled)))
                .isEqualTo(new AuditedAction(
                        "webhook_endpoint.created",
                        "webhook_endpoint",
                        id.publicId().toString(),
                        Map.of("url", URL, "enabled_events", EVENTS, "status", "enabled")));
    }

    @Test
    void recordsAChangeWithTheEndpointBeforeIt() {
        EndpointView moved = endpoint(
                "https://attacker.example.net/hook", EndpointStatus.DISABLED, Optional.of(DisabledReason.BY_TENANT));

        assertThat(AuditedEndpointEvents.updated(new WebhookEndpointUpdated(header(), moved, enabled)))
                .isEqualTo(new AuditedAction(
                        "webhook_endpoint.updated",
                        "webhook_endpoint",
                        id.publicId().toString(),
                        Map.of(
                                "url", "https://attacker.example.net/hook",
                                "enabled_events", EVENTS,
                                "status", "disabled",
                                "disabled_reason", "by_tenant",
                                "previous", Map.of("url", URL, "enabled_events", EVENTS, "status", "enabled"))));
    }

    @Test
    void recordsADeletedEndpoint() {
        assertThat(AuditedEndpointEvents.deleted(new WebhookEndpointDeleted(header(), enabled)))
                .isEqualTo(new AuditedAction(
                        "webhook_endpoint.deleted",
                        "webhook_endpoint",
                        id.publicId().toString(),
                        Map.of("url", URL, "enabled_events", EVENTS, "status", "enabled")));
    }

    @Test
    void recordsARolledSecretWithTheEndOfThePreviousOneAndNeverASecret() {
        Instant end = NOW.plusSeconds(86_400);

        assertThat(AuditedEndpointEvents.secretRolled(new WebhookSecretRolled(header(), enabled, end)))
                .isEqualTo(new AuditedAction(
                        "webhook_endpoint.secret_rolled",
                        "webhook_endpoint",
                        id.publicId().toString(),
                        Map.of(
                                "url",
                                URL,
                                "enabled_events",
                                EVENTS,
                                "status",
                                "enabled",
                                "previous_secret_expires_at",
                                "2026-11-25T15:04:05.123456Z")));
    }

    @Test
    void recordsAnEndpointDisabledForFailingWithWhenItStartedFailing() {
        EndpointView failing = endpoint(URL, EndpointStatus.DISABLED, Optional.of(DisabledReason.FAILING));

        assertThat(AuditedEndpointEvents.disabled(
                        new WebhookEndpointDisabled(header(), failing, NOW.minusSeconds(259_200))))
                .isEqualTo(new AuditedAction(
                        "webhook_endpoint.disabled",
                        "webhook_endpoint",
                        id.publicId().toString(),
                        Map.of(
                                "url", URL,
                                "enabled_events", EVENTS,
                                "status", "disabled",
                                "disabled_reason", "failing",
                                "failing_since", "2026-11-21T15:04:05.123456Z")));
    }

    private static EventHeader header() {
        return new EventHeader(
                new EventId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Environment.TEST,
                new Actor.ApiKey(new ApiKeyId(UUID.randomUUID())),
                "correlation-1",
                NOW);
    }

    private EndpointView endpoint(String url, EndpointStatus status, Optional<DisabledReason> reason) {
        return new EndpointView(
                id, url, Optional.of("Order service"), Set.of("charge.refunded", "charge.paid"), status, reason, NOW);
    }
}
