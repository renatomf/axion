package dev.finaxis.core.billing;

import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Amounts, times and charges for the billing tests. */
public final class BillingFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-11-22T14:30:00Z");
    public static final Instant EXPIRES_AT = CREATED_AT.plus(Duration.ofMinutes(30));

    private BillingFixtures() {}

    public static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, Charge.CURRENCY);
    }

    /** A charge of R$ 100.00, created at {@link #CREATED_AT} and expiring 30 minutes later. */
    public static Charge activeCharge() {
        return Charge.create(new ChargeId(UUID.randomUUID()), brl(10000), CREATED_AT, EXPIRES_AT);
    }
}
