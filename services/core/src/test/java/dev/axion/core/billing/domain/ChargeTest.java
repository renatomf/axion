package dev.axion.core.billing.domain;

import static dev.axion.core.billing.BillingFixtures.CREATED_AT;
import static dev.axion.core.billing.BillingFixtures.EXPIRES_AT;
import static dev.axion.core.billing.BillingFixtures.activeCharge;
import static dev.axion.core.billing.BillingFixtures.brl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.billing.ChargeId;
import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.shared.Money;
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

    @Test
    void expiresOnceItsExpiryHasPassed() {
        Charge charge = activeCharge();

        assertThat(charge.expire(EXPIRES_AT).status()).isEqualTo(ChargeStatus.EXPIRED);
        assertThat(charge.expire(EXPIRES_AT.plusSeconds(3600)).status()).isEqualTo(ChargeStatus.EXPIRED);
    }

    @Test
    void doesNotExpireBeforeItsExpiry() {
        Charge charge = activeCharge();

        assertThatThrownBy(() -> charge.expire(EXPIRES_AT.minusMillis(1))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void expiresOnlyOnce() {
        Charge expired = activeCharge().expire(EXPIRES_AT);

        assertThatThrownBy(() -> expired.expire(EXPIRES_AT)).isInstanceOf(IllegalStateException.class);
    }
}
