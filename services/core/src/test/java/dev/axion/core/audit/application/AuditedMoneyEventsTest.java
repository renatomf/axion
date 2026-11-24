package dev.axion.core.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import dev.axion.core.billing.ChargeDetail;
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
import dev.axion.core.shared.Environment;
import dev.axion.core.shared.EventHeader;
import dev.axion.core.shared.EventId;
import dev.axion.core.shared.Money;
import dev.axion.core.shared.TenantId;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What each movement of money records in the audit trail (events and webhooks design, section 9.4):
 * never the charge's description or metadata.
 */
class AuditedMoneyEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-24T15:04:05.123456Z");
    private static final Money AMOUNT = Money.ofMinor(10_050, Currency.getInstance("BRL"));
    private static final String END_TO_END_ID = "E99990011202611241504abcdefghijk";

    private final ChargeId chargeId = new ChargeId(UUID.randomUUID());
    private final PaymentView payment = new PaymentView(
            new PaymentId(UUID.randomUUID()),
            END_TO_END_ID,
            AMOUNT,
            Money.zero(AMOUNT.currency()),
            NOW,
            PaymentOutcome.FLAGGED,
            Optional.of(ReviewReason.AMOUNT_MISMATCH));

    @Test
    void recordsASettledChargeWithItsAmountAndWhenItWasPaid() {
        var event = new ChargePaid(header(), detail(ChargeStatus.PAID, NOW));

        assertThat(AuditedMoneyEvents.paid(event))
                .isEqualTo(new AuditedAction(
                        "charge.paid",
                        "charge",
                        chargeId.publicId().toString(),
                        Map.of("amount", 10_050L, "currency", "BRL", "paid_at", "2026-11-24T15:04:05.123456Z")));
    }

    @Test
    void recordsAFlaggedPaymentWithItsChargeAndWhyItDidNotSettleIt() {
        var event = new PaymentFlagged(header(), payment, detail(ChargeStatus.ACTIVE, null));

        assertThat(AuditedMoneyEvents.flagged(event))
                .isEqualTo(new AuditedAction(
                        "payment.flagged",
                        "payment",
                        payment.id().publicId().toString(),
                        Map.of(
                                "amount",
                                10_050L,
                                "currency",
                                "BRL",
                                "charge",
                                chargeId.publicId().toString(),
                                "end_to_end_id",
                                END_TO_END_ID,
                                "reason",
                                "amount_mismatch")));
    }

    @Test
    void recordsARefundWithItsChargeAndPayment() {
        var refund = new RefundView(
                new RefundId(UUID.randomUUID()),
                chargeId,
                payment.id(),
                Money.ofMinor(4_000, AMOUNT.currency()),
                RefundStatus.SUCCEEDED,
                NOW);
        var event = new ChargeRefunded(header(), refund, detail(ChargeStatus.PARTIALLY_REFUNDED, NOW));

        assertThat(AuditedMoneyEvents.refunded(event))
                .isEqualTo(new AuditedAction(
                        "charge.refunded",
                        "refund",
                        refund.id().publicId().toString(),
                        Map.of(
                                "amount",
                                4_000L,
                                "currency",
                                "BRL",
                                "charge",
                                chargeId.publicId().toString(),
                                "payment",
                                payment.id().publicId().toString(),
                                "status",
                                "succeeded")));
    }

    private static EventHeader header() {
        return new EventHeader(
                new EventId(UUID.randomUUID()),
                new TenantId(UUID.randomUUID()),
                Environment.TEST,
                new Actor.Job("pix-settlement"),
                "correlation-1",
                NOW);
    }

    private ChargeDetail detail(ChargeStatus status, @Nullable Instant paidAt) {
        var charge = new ChargeView(
                chargeId,
                status,
                AMOUNT,
                Money.zero(AMOUNT.currency()),
                "Mensalidade de novembro",
                Map.of("order_id", "ord_8842"),
                "AXN0123456789ABCDEFGHIJKL",
                "00020101021226850014br.gov.bcb.pix",
                NOW.plusSeconds(1_800),
                paidAt,
                NOW.minusSeconds(60),
                Environment.TEST);
        return new ChargeDetail(charge, List.of(payment), List.of());
    }
}
