package dev.axion.core.events;

import dev.axion.core.access.ApiKeyIssued;
import dev.axion.core.access.ApiKeyRevoked;
import dev.axion.core.access.ApiKeyRolled;
import dev.axion.core.access.ApiKeyView;
import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.PaymentOutcome;
import dev.axion.core.billing.PaymentView;
import dev.axion.core.billing.RefundId;
import dev.axion.core.billing.RefundStatus;
import dev.axion.core.billing.RefundView;
import dev.axion.core.billing.ReviewReason;
import dev.axion.core.shared.Actor;
import dev.axion.core.shared.ApiKeyId;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.MemberId;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantId;
import dev.axion.core.tenancy.Permission;
import dev.axion.core.webhooks.DisabledReason;
import dev.axion.core.webhooks.EndpointStatus;
import dev.axion.core.webhooks.EndpointView;
import dev.axion.core.webhooks.WebhookEndpointCreated;
import dev.axion.core.webhooks.WebhookEndpointDeleted;
import dev.axion.core.webhooks.WebhookEndpointId;
import dev.axion.core.webhooks.WebhookEndpointUpdated;
import dev.axion.core.webhooks.WebhookSecretRolled;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** One of each domain event of the catalog (events and webhooks design, section 3.3), for tests. */
public final class EventFixtures {

    static final Instant NOW = Instant.parse("2026-11-23T14:03:11.482913Z");
    static final Money AMOUNT = Money.ofMinor(10_000, Currency.getInstance("BRL"));

    private EventFixtures() {}

    /** One of each domain event of the catalog, with every optional field filled. */
    public static Stream<DomainEvent> catalog() {
        var charge = charge();
        var payment = new PaymentView(
                new PaymentId(UUID.randomUUID()),
                "E99990011202611231403abcdefghij",
                AMOUNT,
                Money.zero(AMOUNT.currency()),
                NOW,
                PaymentOutcome.FLAGGED,
                Optional.of(ReviewReason.AMOUNT_MISMATCH));
        var refund = new RefundView(
                new RefundId(UUID.randomUUID()),
                charge.charge().id(),
                payment.id(),
                AMOUNT,
                RefundStatus.SUCCEEDED,
                NOW);
        var key = key();
        return Stream.of(
                new ChargeCreated(header(new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()))), charge),
                new ChargePaid(header(new Actor.Job("pix-settlement")), charge),
                new ChargeExpired(header(new Actor.Job("charge-expiry")), charge),
                new PaymentFlagged(header(new Actor.Job("pix-settlement")), payment, charge),
                new ChargeRefunded(header(new Actor.Member(new MemberId(UUID.randomUUID()))), refund, charge),
                new ApiKeyIssued(header(new Actor.Member(new MemberId(UUID.randomUUID()))), key),
                new ApiKeyRolled(header(new Actor.Member(new MemberId(UUID.randomUUID()))), key, key),
                new ApiKeyRevoked(header(new Actor.Member(new MemberId(UUID.randomUUID()))), key),
                new WebhookEndpointCreated(header(new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()))), endpoint()),
                new WebhookEndpointUpdated(
                        header(new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()))), endpoint(), endpoint()),
                new WebhookEndpointDeleted(header(new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()))), endpoint()),
                new WebhookSecretRolled(header(new Actor.ApiKey(new ApiKeyId(UUID.randomUUID()))), endpoint(), NOW));
    }

    private static EventHeader header(Actor actor) {
        return new EventHeader(
                new EventId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Environment.TEST,
                actor,
                "correlation-1",
                NOW);
    }

    // Every optional field filled, so each is written and read back.
    private static ChargeDetail charge() {
        var view = new ChargeView(
                new ChargeId(UUID.randomUUID()),
                ChargeStatus.PAID,
                AMOUNT,
                Money.zero(AMOUNT.currency()),
                "Mensalidade de novembro",
                Map.of("order_id", "ord_8842"),
                "AXN0123456789ABCDEFGHIJK",
                "00020101021226850014br.gov.bcb.pix",
                NOW.plusSeconds(1_800),
                NOW,
                NOW.minusSeconds(60),
                Environment.TEST);
        return new ChargeDetail(view, List.of(), List.of());
    }

    private static EndpointView endpoint() {
        return new EndpointView(
                new WebhookEndpointId(UUID.randomUUID()),
                "https://example.com/axion/webhooks",
                Optional.of("Order service"),
                Set.of("charge.paid", "charge.refunded"),
                EndpointStatus.DISABLED,
                Optional.of(DisabledReason.BY_TENANT),
                NOW);
    }

    private static ApiKeyView key() {
        return new ApiKeyView(
                new ApiKeyId(UUID.randomUUID()),
                "Checkout",
                "a1b2",
                Set.of(Permission.CHARGES_READ, Permission.CHARGES_WRITE),
                NOW,
                Optional.of(NOW.plusSeconds(3_600)),
                Optional.of(NOW),
                Optional.of(NOW));
    }
}
