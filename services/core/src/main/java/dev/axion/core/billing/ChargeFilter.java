package dev.axion.core.billing;

import java.time.Instant;
import java.util.Optional;

/**
 * Which of the tenant's charges a list holds (billing design, section 12.2); an empty part does not
 * filter.
 *
 * @param createdFrom the earliest creation time, inclusive
 * @param createdBefore the creation time all charges precede, exclusive
 */
public record ChargeFilter(
        Optional<ChargeStatus> status, Optional<Instant> createdFrom, Optional<Instant> createdBefore) {

    public static ChargeFilter all() {
        return new ChargeFilter(Optional.empty(), Optional.empty(), Optional.empty());
    }
}
