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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes public events as CloudEvents 1.0 in structured JSON mode (events and webhooks design,
 * section 5.1). Each event's {@code data.object} is the resource as the public API returns it at the
 * event's time, written with the API's own mapper and representation: billing's here, and those of
 * a module that consumes the catalog by the {@link PublicEventWriter} it contributes.
 */
@Component
class CloudEventCatalog implements EventCatalog {

    private static final String CONTRACTS = "https://github.com/renatomf/axion/blob/main/services/core/openapi/";

    private static final String VERSION = "v1";

    // UTC with milliseconds, whatever precision the event's instant has.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(
                    "uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC);

    private static final Set<String> BILLING_TYPES =
            Set.of("charge.created", "charge.paid", "charge.expired", "payment.flagged", "charge.refunded");

    private final JsonMapper json;
    private final Map<Class<?>, PublicEventWriter<?>> writers;
    private final Set<String> types;

    CloudEventCatalog(JsonMapper json, List<PublicEventWriter<?>> writers) {
        this.json = json;
        Map<Class<?>, PublicEventWriter<?>> byEvent = new HashMap<>();
        Set<String> types = new HashSet<>(BILLING_TYPES);
        for (PublicEventWriter<?> writer : writers) {
            // Two writers of one event or one type would make the event's public form depend on bean order.
            if (byEvent.putIfAbsent(writer.event(), writer) != null || !types.add(writer.type())) {
                throw new IllegalStateException(
                        "A second writer of " + writer.event().getSimpleName() + " or of type " + writer.type());
            }
        }
        this.writers = Map.copyOf(byEvent);
        this.types = Set.copyOf(types);
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
                    default -> ofWriter(event);
                });
    }

    @Override
    public Set<String> publicTypes() {
        return types;
    }

    private PublicEvent ofCharge(DomainEvent event, String type, ChargeDetail charge) {
        return write(event, type, charge.charge().id().publicId(), new ChargeData(ChargeDetailResponse.of(charge)));
    }

    private @Nullable PublicEvent ofWriter(DomainEvent event) {
        PublicEventWriter<?> writer = writers.get(event.getClass());
        return writer == null ? null : ofWriter(event, writer);
    }

    private <E extends DomainEvent> PublicEvent ofWriter(DomainEvent event, PublicEventWriter<E> writer) {
        E typed = writer.event().cast(event);
        return write(event, writer.type(), writer.contract(), writer.subject(typed), writer.data(typed));
    }

    private PublicEvent write(DomainEvent event, String type, PublicId subject, Object data) {
        return write(event, type, EventContract.V1, subject, data);
    }

    private PublicEvent write(DomainEvent event, String type, EventContract contract, PublicId subject, Object data) {
        var envelope = new CloudEvent(
                "1.0",
                event.id().publicId().toString(),
                "urn:axion:" + event.environment().name().toLowerCase(Locale.ROOT),
                type,
                subject.toString(),
                TIME.format(event.occurredAt()),
                "application/json",
                CONTRACTS + contract.document() + "#" + type + "." + VERSION,
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
