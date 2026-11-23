package dev.finaxis.core.billing;

import java.util.List;
import java.util.Optional;

/** One page of the tenant's charges, newest first; {@code next} is empty on the last page. */
public record ChargePage(List<ChargeView> charges, Optional<ChargeCursor> next) {

    public ChargePage {
        charges = List.copyOf(charges);
    }

    public boolean hasMore() {
        return next.isPresent();
    }
}
