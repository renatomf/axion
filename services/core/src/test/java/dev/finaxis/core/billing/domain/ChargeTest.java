package dev.finaxis.core.billing.domain;

import static dev.finaxis.core.billing.BillingFixtures.CREATED_AT;
import static dev.finaxis.core.billing.BillingFixtures.EXPIRES_AT;
import static dev.finaxis.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.billing.ChargeId;
import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.shared.Money;
import java.time.Duration;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChargeTest {

    private static final ChargeId ID = new ChargeId(UUID.randomUUID());

    @Test
    void aNewChargeIsActive() {
        Charge charge = Charge.create(ID, brl(10000), CREATED_AT, EXPIRES_AT);

        assertThat(charge.status()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(charge.amount()).isEqualTo(brl(10000));
    }

    @Test
    void acceptsTheSmallestAndTheLargestAmount() {
        assertThat(Charge.create(ID, brl(1), CREATED_AT, EXPIRES_AT).amount()).isEqualTo(brl(1));
        assertThat(Charge.create(ID, brl(Charge.MAX_AMOUNT_MINOR), CREATED_AT, EXPIRES_AT)
                        .amount())
                .isEqualTo(brl(Charge.MAX_AMOUNT_MINOR));
    }

    @Test
    void rejectsAnAmountOutsideItsRange() {
        for (long minorUnits : new long[] {0, -1, Charge.MAX_AMOUNT_MINOR + 1}) {
            assertThatThrownBy(() -> Charge.create(ID, brl(minorUnits), CREATED_AT, EXPIRES_AT))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("amount");
        }
    }

    @Test
    void rejectsACurrencyOtherThanTheReal() {
        Money dollars = Money.ofMinor(10000, Currency.getInstance("USD"));

        assertThatThrownBy(() -> Charge.create(ID, dollars, CREATED_AT, EXPIRES_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BRL");
    }

    @Test
    void livesAtLeastAMinute() {
        assertThat(Charge.create(ID, brl(10000), CREATED_AT, CREATED_AT.plus(Charge.MIN_LIFETIME))
                        .expiresAt())
                .isEqualTo(CREATED_AT.plus(Charge.MIN_LIFETIME));
        assertThatThrownBy(() -> Charge.create(
                        ID,
                        brl(10000),
                        CREATED_AT,
                        CREATED_AT.plus(Charge.MIN_LIFETIME).minus(Duration.ofMillis(1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expires");
    }
}
