package dev.axion.core.events;

import dev.axion.core.billing.ChargeCreated;
import dev.axion.core.billing.ChargeDetail;
import dev.axion.core.billing.ChargeExpired;
import dev.axion.core.billing.ChargePaid;
import dev.axion.core.billing.ChargeRefunded;
import dev.axion.core.billing.PaymentFlagged;
import dev.axion.core.billing.representation.ChargeDetailResponse;
import dev.axion.core.billing.representation.PaymentResponse;
import dev.axion.core.shared.DomainEvent;
import dev.axion.core.shared.PublicId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes public events as CloudEvents 1.0 in structured JSON mode (events and webhooks design,
 * section 5.1). Each event's {@code data.object} is the resource as the public API returns it at the
 * event's time, written with the API's own mapper and representation.
 */
@Component
class CloudEventCatalog implements EventCatalog {

    private static final String SCHEMAS = "https://github.com/renatomf/axion/blob/main/services/core/openapi/v1.yaml#";

    private static final String VERSION = "v1";

    // UTC with milliseconds, whatever precision the event's instant has.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Set<String> TYPES =
            Set.of("charge.created", "charge.paid", "charge.expired", "payment.flagged", "charge.refunded");

    private final JsonMapper json;

    CloudEventCatalog(JsonMapper json) {
        this.json = json;
    }

    @Override
    public Optional<PublicEvent> render(DomainEvent event) {
        return Optional.ofNullable(
                switch (event) {
                    case ChargeCreated created -> ofCharge(event, "charge.created", created.charge());
                    case ChargePaid paid -> ofCharge(event, "charge.paid", paid.charge());
                    case ChargeExpired expired -> ofCharge(event, "charge.expired", expired.charge());
                    case PaymentFlagged flagged ->
                        write(
                                event,
                                "payment.flagged",
                                flagged.payment().id().publicId(),
                                new FlaggedPayment(
                                        PaymentResponse.of(flagged.payment()),
                                        ChargeDetailResponse.of(flagged.charge())));
                    case ChargeRefunded refunded ->
                        write(
                                event,
                                "charge.refunded",
                                refunded.charge().charge().id().publicId(),
                                new RefundedCharge(
                                        ChargeDetailResponse.of(refunded.charge()),
                                        refunded.refund().id().publicId().toString()));
                    default -> null;
                });
    }

    @Override
    public Set<String> publicTypes() {
        return TYPES;
    }

    private PublicEvent ofCharge(DomainEvent event, String type, ChargeDetail charge) {
        return write(event, type, charge.charge().id().publicId(), new ChargeData(ChargeDetailResponse.of(charge)));
    }

    private PublicEvent write(DomainEvent event, String type, PublicId subject, Object data) {
        var envelope = new CloudEvent(
                "1.0",
                event.id().publicId().toString(),
                "urn:axion:" + event.environment().name().toLowerCase(Locale.ROOT),
                type,
                subject.toString(),
                TIME.format(event.occurredAt()),
                "application/json",
                SCHEMAS + type + "." + VERSION,
                data);
        return new PublicEvent(event.id(), type, event.occurredAt(), json.writeValueAsString(envelope));
    }

    // In the order of the CloudEvents specification's attributes, so the body reads as its examples do.
    private record CloudEvent(
            String specversion,
            String id,
            String source,
            String type,
            String subject,
            String time,
            String datacontenttype,
            String dataschema,
            Object data) {}

    private record ChargeData(ChargeDetailResponse object) {}

    /** The payment, with the charge as it is after it. */
    private record FlaggedPayment(PaymentResponse object, ChargeDetailResponse charge) {}

    /** The charge after the refund, which {@code refund} names among its refunds. */
    private record RefundedCharge(ChargeDetailResponse object, String refund) {}
}
