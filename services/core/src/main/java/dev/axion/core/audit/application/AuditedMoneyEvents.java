package dev.axion.core.audit.application;

import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.billing.PaymentView;
import dev.axion.core.billing.RefundView;
import dev.axion.core.shared.Money;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;

/**
 * Records who moved money (events and webhooks design, section 9.4): every charge settled, payment
 * flagged and refund made, with its amount. A record never holds the charge's description or
 * metadata, which are the tenant's own data.
 *
 * <p>Each listener runs without a transaction of its own: {@link AuditTrail} binds the event's tenant
 * before it begins one.
 */
@Component
class AuditedMoneyEvents {

    private final AuditTrail trail;

    AuditedMoneyEvents(AuditTrail trail) {
        this.trail = trail;
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargePaid event) {
        trail.append(event, paid(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(PaymentFlagged event) {
        trail.append(event, flagged(event));
    }

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    void on(ChargeRefunded event) {
        trail.append(event, refunded(event));
    }

    static AuditedAction paid(ChargePaid event) {
        ChargeView charge = event.charge().charge();
        Map<String, Object> details = amount(charge.amount());
        details.put("paid_at", Objects.requireNonNull(charge.paidAt()).toString());
        return new AuditedAction("charge.paid", "charge", charge.id().publicId().toString(), details);
    }

    /** Recorded on the payment, which was credited without settling its charge. */
    static AuditedAction flagged(PaymentFlagged event) {
        PaymentView payment = event.payment();
        Map<String, Object> details = amount(payment.amount());
        details.put("charge", event.charge().charge().id().publicId().toString());
        details.put("end_to_end_id", payment.endToEndId());
        payment.reviewReason().ifPresent(reason -> details.put("reason", lowercase(reason)));
        return new AuditedAction(
                "payment.flagged", "payment", payment.id().publicId().toString(), details);
    }

    static AuditedAction refunded(ChargeRefunded event) {
        RefundView refund = event.refund();
        Map<String, Object> details = amount(refund.amount());
        details.put("charge", refund.charge().publicId().toString());
        details.put("payment", refund.payment().publicId().toString());
        details.put("status", lowercase(refund.status()));
        return new AuditedAction(
                "charge.refunded", "refund", refund.id().publicId().toString(), details);
    }

    // In minor units, as the API writes amounts.
    private static Map<String, Object> amount(Money money) {
        Map<String, Object> details = new HashMap<>();
        details.put("amount", money.minorUnits());
        details.put("currency", money.currency().getCurrencyCode());
        return details;
    }

    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
