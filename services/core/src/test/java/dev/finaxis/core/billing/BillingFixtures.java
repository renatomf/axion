package dev.finaxis.core.billing;

import dev.finaxis.core.billing.domain.Charge;
import dev.finaxis.core.billing.domain.IncomingPayment;
import dev.finaxis.core.shared.Money;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Amounts, times and charges for the billing tests. */
public final class BillingFixtures {

    public static final Instant CREATED_AT = Instant.parse("2026-11-22T14:30:00Z");
    public static final Instant EXPIRES_AT = CREATED_AT.plus(Duration.ofMinutes(30));

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private BillingFixtures() {}

    public static Money brl(long minorUnits) {
        return Money.ofMinor(minorUnits, Charge.CURRENCY);
    }

    /** A charge of R$ 100.00, created at {@link #CREATED_AT} and expiring 30 minutes later. */
    public static Charge activeCharge() {
        return Charge.create(new ChargeId(UUID.randomUUID()), brl(10000), CREATED_AT, EXPIRES_AT);
    }

    /** A Pix credit of {@code minorUnits}, accepted by the rails at {@code acceptedAt}. */
    public static IncomingPayment payment(long minorUnits, Instant acceptedAt) {
        return new IncomingPayment(new PaymentId(UUID.randomUUID()), endToEndId(), brl(minorUnits), acceptedAt);
    }

    /** A new EndToEndId in the rails' layout, from the first payers' institution of the simulator. */
    public static String endToEndId() {
        var suffix = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            suffix.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return "E99990011202611221430" + suffix;
    }
}
