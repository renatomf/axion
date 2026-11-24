package dev.axion.core.webhooks.web;

import dev.axion.core.shared.PublicId;
import dev.axion.core.webhooks.DeliveryCursor;
import dev.axion.core.webhooks.DeliveryFilter;
import dev.axion.core.webhooks.DeliveryStatus;
import dev.axion.core.webhooks.WebhookDeliveryId;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** A delivery's ID, and the filters of the delivery log, as a path or a query names them. */
final class DeliveryQuery {

    /** The contract's pattern for a delivery's ID. */
    static final String ID = "whd_[0-7][0-9a-hjkmnp-tv-z]{25}";

    /** The contract's statuses of a delivery. */
    static final String STATUS = "pending|succeeded|dead|canceled";

    /** The contract's event types a delivery sends: those of the catalog endpoints receive. */
    static final String EVENT_TYPE =
            "charge\\.created|charge\\.paid|charge\\.expired|payment\\.flagged|charge\\.refunded";

    private DeliveryQuery() {}

    // A malformed ID answers 404, like another tenant's delivery, not 400.
    static WebhookDeliveryId inPath(String text) {
        try {
            return parse(text);
        } catch (IllegalArgumentException malformed) {
            throw WebhookProblems.notFound();
        }
    }

    /** The page after {@code starting_after}, already checked against {@link #ID}, or the first. */
    static DeliveryCursor cursor(@Nullable String startingAfter) {
        return startingAfter == null ? DeliveryCursor.first() : DeliveryCursor.after(parse(startingAfter));
    }

    /** The filter of {@code status} and {@code type}, already checked against their patterns. */
    static DeliveryFilter filter(@Nullable String status, @Nullable String type) {
        return new DeliveryFilter(
                Optional.ofNullable(status).map(name -> DeliveryStatus.valueOf(name.toUpperCase(Locale.ROOT))),
                Optional.ofNullable(type));
    }

    private static WebhookDeliveryId parse(String text) {
        return new WebhookDeliveryId(
                PublicId.parse(WebhookDeliveryId.PREFIX, text).uuid());
    }
}
