package dev.axion.core.billing.domain;

import static dev.axion.core.billing.BillingFixtures.CREATED_AT;
import static dev.axion.core.billing.BillingFixtures.EXPIRES_AT;
import static dev.axion.core.billing.BillingFixtures.activeCharge;
import static dev.axion.core.billing.BillingFixtures.brl;
import static dev.axion.core.billing.BillingFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.axion.core.billing.ChargeStatus;
import dev.axion.core.billing.PaymentId;
import dev.axion.core.billing.PaymentOutcome;
import dev.axion.core.billing.ReviewReason;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The outcome of each payment a charge receives (billing design, section 8.2). */
class ChargePaymentsTest {

    private static final Instant ON_TIME = CREATED_AT.plusSeconds(60);
    private static final Instant NOW = ON_TIME.plusSeconds(1);

    @Test
    void aPaymentOfTheAmountAcceptedBeforeExpirySettlesTheCharge() {
        IncomingPayment incoming = payment(10000, ON_TIME);

        Receipt receipt = activeCharge().receive(incoming, NOW);

        assertThat(receipt.payment().outcome()).isEqualTo(PaymentOutcome.SETTLED);
        assertThat(receipt.payment().reviewReason()).isEmpty();
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(receipt.charge().paidAt()).contains(ON_TIME);
        assertThat(receipt.charge().settlingPayment()).contains(receipt.payment());
    }

    @Test
    void knowsWhichPaymentsItReceived() {
        IncomingPayment incoming = payment(10000, ON_TIME);

        Charge paid = activeCharge().receive(incoming, NOW).charge();

        assertThat(paid.hasReceived(incoming.endToEndId())).isTrue();
        assertThat(activeCharge().hasReceived(incoming.endToEndId())).isFalse();
    }

    @Test
    void aPaymentOneMillisecondBeforeExpiryIsOnTime() {
        Receipt receipt = activeCharge().receive(payment(10000, EXPIRES_AT.minusMillis(1)), NOW);

        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
    }

    @Test
    void aPaymentAcceptedAtExpiryIsLate() {
        Receipt receipt = activeCharge().receive(payment(10000, EXPIRES_AT), EXPIRES_AT);

        assertFlagged(receipt, ReviewReason.CHARGE_EXPIRED);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.EXPIRED);
    }

    /** The rails' acceptance time decides, not when the core heard of the payment. */
    @Test
    void aPaymentAcceptedBeforeExpiryPaysAnExpiredCharge() {
        Charge expired = activeCharge().expire(EXPIRES_AT);

        Receipt receipt = expired.receive(payment(10000, ON_TIME), EXPIRES_AT.plusSeconds(3600));

        assertThat(receipt.payment().outcome()).isEqualTo(PaymentOutcome.SETTLED);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(receipt.charge().paidAt()).contains(ON_TIME);
    }

    @Test
    void aLatePaymentLeavesAnExpiredChargeExpired() {
        Charge expired = activeCharge().expire(EXPIRES_AT);

        Receipt receipt = expired.receive(payment(10000, EXPIRES_AT.plusSeconds(10)), EXPIRES_AT.plusSeconds(11));

        assertFlagged(receipt, ReviewReason.CHARGE_EXPIRED);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.EXPIRED);
    }

    @Test
    void aPaymentOfAnotherAmountIsFlagged() {
        Receipt receipt = activeCharge().receive(payment(9999, ON_TIME), NOW);

        assertFlagged(receipt, ReviewReason.AMOUNT_MISMATCH);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(receipt.charge().paidAt()).isEmpty();
    }

    @Test
    void aSecondPaymentOfAPaidChargeIsFlagged() {
        Charge paid = activeCharge().receive(payment(10000, ON_TIME), NOW).charge();

        Receipt receipt = paid.receive(payment(10000, ON_TIME), NOW);

        assertFlagged(receipt, ReviewReason.CHARGE_ALREADY_PAID);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(receipt.charge().payments()).hasSize(2);
    }

    /** Already paid is checked first: a late second payment of a paid charge does not expire it. */
    @Test
    void aLateSecondPaymentOfAPaidChargeIsFlaggedAsAlreadyPaid() {
        Charge paid = activeCharge().receive(payment(10000, ON_TIME), NOW).charge();

        Receipt receipt = paid.receive(payment(10000, EXPIRES_AT.plusSeconds(10)), EXPIRES_AT.plusSeconds(11));

        assertFlagged(receipt, ReviewReason.CHARGE_ALREADY_PAID);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
    }

    /** Settlement may reach a charge before the expiry job does, and expires it itself. */
    @Test
    void anActiveChargePastItsExpiryIsExpiredFirst() {
        Receipt receipt = activeCharge().receive(payment(9999, ON_TIME), EXPIRES_AT.plusSeconds(1));

        assertFlagged(receipt, ReviewReason.AMOUNT_MISMATCH);
        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.EXPIRED);
    }

    @Test
    void aPaymentAfterAFlaggedOneStillSettles() {
        Charge charge = activeCharge().receive(payment(9999, ON_TIME), NOW).charge();

        Receipt receipt = charge.receive(payment(10000, ON_TIME), NOW);

        assertThat(receipt.charge().status()).isEqualTo(ChargeStatus.PAID);
        assertThat(receipt.charge().payments())
                .extracting(Payment::outcome)
                .containsExactly(PaymentOutcome.FLAGGED, PaymentOutcome.SETTLED);
    }

    @Test
    void refusesAPaymentItAlreadyHas() {
        IncomingPayment incoming = payment(10000, ON_TIME);
        Charge paid = activeCharge().receive(incoming, NOW).charge();

        assertThatThrownBy(() -> paid.receive(incoming, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(incoming.endToEndId());
    }

    @Test
    void cannotBeBuiltPaidWithoutASettlingPayment() {
        Charge active = activeCharge();

        assertThatThrownBy(() -> new Charge(
                        active.id(), active.amount(), CREATED_AT, EXPIRES_AT, ChargeStatus.PAID, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotBeBuiltActiveWithASettlingPayment() {
        Charge paid = activeCharge().receive(payment(10000, ON_TIME), NOW).charge();

        assertThatThrownBy(() -> new Charge(
                        paid.id(),
                        paid.amount(),
                        CREATED_AT,
                        EXPIRES_AT,
                        ChargeStatus.ACTIVE,
                        paid.payments(),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotBeBuiltWithTwoSettlingPayments() {
        Charge paid = activeCharge().receive(payment(10000, ON_TIME), NOW).charge();
        Payment second = Payment.settled(payment(10000, ON_TIME));

        assertThatThrownBy(() -> new Charge(
                        paid.id(),
                        paid.amount(),
                        CREATED_AT,
                        EXPIRES_AT,
                        ChargeStatus.PAID,
                        List.of(paid.payments().getFirst(), second),
                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most one");
    }

    @Test
    void aFlaggedPaymentAlwaysHasItsReason() {
        assertThatThrownBy(() -> new Payment(
                        new PaymentId(UUID.randomUUID()),
                        "E99990011202611221430aB3dE5gH7jK",
                        brl(10000),
                        ON_TIME,
                        PaymentOutcome.FLAGGED,
                        Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertFlagged(Receipt receipt, ReviewReason reason) {
        assertThat(receipt.payment().outcome()).isEqualTo(PaymentOutcome.FLAGGED);
        assertThat(receipt.payment().reviewReason()).contains(reason);
        assertThat(receipt.charge().payments().getLast()).isEqualTo(receipt.payment());
    }
}
