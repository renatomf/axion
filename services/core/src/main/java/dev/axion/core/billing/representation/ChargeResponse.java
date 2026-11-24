package dev.axion.core.billing.representation;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.axion.core.billing.ChargeView;
import dev.axion.core.shared.Environment;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public record ChargeResponse(
        String id,
        String object,
        String status,
        long amount,
        String currency,
        @JsonProperty("amount_refunded") long amountRefunded,
        @Nullable String description,
        Map<String, String> metadata,
        Pix pix,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("paid_at") @Nullable Instant paidAt,
        @JsonProperty("created_at") Instant createdAt,
        boolean livemode) {

    public record Pix(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("br_code") String brCode) {}

    public static ChargeResponse of(ChargeView charge) {
        return new ChargeResponse(
                charge.id().publicId().toString(),
                "charge",
                charge.status().name().toLowerCase(Locale.ROOT),
                charge.amount().minorUnits(),
                charge.amount().currency().getCurrencyCode(),
                charge.amountRefunded().minorUnits(),
                charge.description(),
                // Sorted, so the response does not depend on map iteration order.
                new TreeMap<>(charge.metadata()),
                new Pix(charge.transactionId(), charge.brCode()),
                charge.expiresAt(),
                charge.paidAt(),
                charge.createdAt(),
                charge.environment() == Environment.LIVE);
    }
}
