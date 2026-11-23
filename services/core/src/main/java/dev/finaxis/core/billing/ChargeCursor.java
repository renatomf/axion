package dev.finaxis.core.billing;

import java.util.Optional;

/**
 * Where a page of {@link BillingApi#charges} starts: after a charge, not at a page number, so a deep
 * page costs the same and new charges do not shift the pages.
 */
public record ChargeCursor(Optional<ChargeId> after) {

    public static ChargeCursor first() {
        return new ChargeCursor(Optional.empty());
    }

    public static ChargeCursor after(ChargeId last) {
        return new ChargeCursor(Optional.of(last));
    }
}
