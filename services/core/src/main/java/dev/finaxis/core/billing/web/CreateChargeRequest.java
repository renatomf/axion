package dev.finaxis.core.billing.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.finaxis.core.billing.ChargeRequest;
import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.shared.Money;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;

// The limits of ChargeRequest, checked here so that a breach is a 422 naming the field. The
// expiry's are in seconds: an annotation cannot read ChargeRequest's Durations.
record CreateChargeRequest(
        @NotNull @Min(1) @Max(Charge.MAX_AMOUNT_MINOR) Long amount,

        @NotNull @Pattern(regexp = "BRL", message = "must be BRL")
        String currency,

        @CodePointLength(min = 1, max = ChargeRequest.MAX_DESCRIPTION_LENGTH) @Nullable
        String description,

        @JsonProperty("expires_in") @Min(60) @Max(604_800) @Nullable
        Long expiresIn,

        @Size(max = ChargeRequest.MAX_METADATA_KEYS) @Nullable
        Map<
                        @CodePointLength(min = 1, max = ChargeRequest.MAX_METADATA_KEY_LENGTH) String,
                        @NotNull @CodePointLength(max = ChargeRequest.MAX_METADATA_VALUE_LENGTH) String>
                metadata) {

    ChargeRequest toChargeRequest() {
        return new ChargeRequest(
                Money.ofMinor(amount, Charge.CURRENCY),
                description,
                expiresIn == null ? ChargeRequest.DEFAULT_EXPIRES_IN : Duration.ofSeconds(expiresIn),
                metadata == null ? Map.of() : metadata);
    }
}
