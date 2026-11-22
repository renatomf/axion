package dev.finaxis.core.billing.domain;

import static dev.finaxis.core.billing.BillingFixtures.CREATED_AT;
import static dev.finaxis.core.billing.BillingFixtures.EXPIRES_AT;
import static dev.finaxis.core.billing.BillingFixtures.activeCharge;
import static dev.finaxis.core.billing.BillingFixtures.brl;
import static dev.finaxis.core.billing.BillingFixtures.payment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.finaxis.core.billing.ChargeStatus;
import dev.finaxis.core.billing.PaymentId;
import dev.finaxis.core.billing.RefundId;
import dev.finaxis.core.billing.RefundRefusal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Refunds of a charge's payments (billing design, section 10.2). */
class ChargeRefundsTest {

    private static final Instant ACCEPTED_AT = CREATED_AT.plusSeconds(60);
    private static final Instant NOW = ACCEPTED_AT.plusSeconds(3600);

    @Test
    void aFullRefundOfTheSettlingPaymentRefundsTheCharge() {
        Charge paid = paidCharge();

        Charge refunded = refunded(paid.refund(newId(), Optional.empty(), Optional.empty(), NOW));

        assertThat(refunded.status()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(refunded.amountRefunded()).isEqualTo(brl(10000));
        assertThat(refunded.refunds()).singleElement().satisfies(refund -> {
            assertThat(refund.amount()).isEqualTo(brl(10000));
            assertThat(refund.payment()).isEqualTo(paid.payments().getFirst().id());
            assertThat(refund.createdAt()).isEqualTo(NOW);
        });
    }

    @Test
    void partialRefundsReturnThePaymentInParts() {
        Charge partly = refunded(paidCharge().refund(newId(), Optional.empty(), Optional.of(brl(3000)), NOW));
        assertThat(partly.status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);
        assertThat(partly.amountRefunded()).isEqualTo(brl(3000));

        Charge more = refunded(partly.refund(newId(), Optional.empty(), Optional.of(brl(2000)), NOW));
        assertThat(more.status()).isEqualTo(ChargeStatus.PARTIALLY_REFUNDED);

        Charge rest = refunded(more.refund(newId(), Optional.empty(), Optional.empty(), NOW));
        assertThat(rest.status()).isEqualTo(ChargeStatus.REFUNDED);
        assertThat(rest.refunds()).extracting(Refund::amount).containsExactly(brl(3000), brl(2000), brl(5000));
    }

    @Test
    void refusesMoreThanRemainsOfThePayment() {
        Charge partly = refunded(paidCharge().refund(newId(), Optional.empty(), Optional.of(brl(9000)), NOW));

        assertRefused(
                partly.refund(newId(), Optional.empty(), Optional.of(brl(1001)), NOW),
                RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE);
        assertThat(refunded(partly.refund(newId(), Optional.empty(), Optional.of(brl(1000)), NOW))
                        .status())
                .isEqualTo(ChargeStatus.REFUNDED);
    }

    @Test
    void refusesARefundOfAPaymentAlreadyRefundedInFull() {
        Charge refunded = refunded(paidCharge().refund(newId(), Optional.empty(), Optional.empty(), NOW));

        assertRefused(
                refunded.refund(newId(), Optional.empty(), Optional.empty(), NOW),
                RefundRefusal.AMOUNT_EXCEEDS_REFUNDABLE);
    }

    @Test
    void refusesARefundOfAChargeWithoutPayments() {
        assertRefused(
                activeCharge().refund(newId(), Optional.empty(), Optional.empty(), NOW), RefundRefusal.CHARGE_NOT_PAID);
    }

    @Test
    void requiresThePaymentWhenTheChargeHasSeveral() {
        Charge twice = paidCharge().receive(payment(10000, ACCEPTED_AT), NOW).charge();

        assertRefused(twice.refund(newId(), Optional.empty(), Optional.empty(), NOW), RefundRefusal.PAYMENT_REQUIRED);
    }

    @Test
    void refusesAPaymentOfAnotherCharge() {
        PaymentId elsewhere = new PaymentId(UUID.randomUUID());

        assertRefused(
                paidCharge().refund(newId(), Optional.of(elsewhere), Optional.empty(), NOW),
                RefundRefusal.UNKNOWN_PAYMENT);
    }

    /** A flagged payment is the tenant's to give back; the charge's status describes its settling payment. */
    @Test
    void refundingAFlaggedPaymentLeavesTheChargeAsItWas() {
        Charge twice = paidCharge().receive(payment(10000, ACCEPTED_AT), NOW).charge();
        Payment flagged = twice.payments().getLast();

        Charge refunded = refunded(twice.refund(newId(), Optional.of(flagged.id()), Optional.empty(), NOW));

        assertThat(refunded.status()).isEqualTo(ChargeStatus.PAID);
        assertThat(refunded.amountRefunded()).isEqualTo(brl(0));
        assertThat(refunded.refundedFrom(flagged.id())).isEqualTo(brl(10000));
    }

    @Test
    void aFlaggedPaymentOfAnUnpaidChargeCanBeRefunded() {
        Charge mismatched =
                activeCharge().receive(payment(9999, ACCEPTED_AT), ACCEPTED_AT).charge();

        Charge refunded = refunded(mismatched.refund(newId(), Optional.empty(), Optional.empty(), NOW));

        assertThat(refunded.status()).isEqualTo(ChargeStatus.ACTIVE);
        assertThat(refunded.refunds())
                .singleElement()
                .extracting(Refund::amount)
                .isEqualTo(brl(9999));
    }

    @Test
    void refundsWithinNinetyDaysOfThePayment() {
        Instant lastMoment = ACCEPTED_AT.plus(Charge.REFUND_WINDOW);

        assertThat(paidCharge().refund(newId(), Optional.empty(), Optional.empty(), lastMoment))
                .isInstanceOf(RefundDecision.Refunded.class);
        assertRefused(
                paidCharge().refund(newId(), Optional.empty(), Optional.empty(), lastMoment.plusMillis(1)),
                RefundRefusal.REFUND_WINDOW_CLOSED);
    }

    @Test
    void refusesTheRefundPastTheLimit() {
        Charge charge = paidCharge();
        for (int i = 0; i < Charge.MAX_REFUNDS; i++) {
            charge = refunded(charge.refund(newId(), Optional.empty(), Optional.of(brl(1)), NOW));
        }

        assertRefused(
                charge.refund(newId(), Optional.empty(), Optional.of(brl(1)), NOW), RefundRefusal.REFUND_LIMIT_REACHED);
    }

    @Test
    void rejectsAnAmountThatIsNotPositive() {
        assertThatThrownBy(() -> paidCharge().refund(newId(), Optional.empty(), Optional.of(brl(0)), NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cannotBeBuiltWithRefundsBeyondItsPayment() {
        Charge paid = paidCharge();
        Refund tooMuch = new Refund(newId(), paid.payments().getFirst().id(), brl(10001), NOW);

        assertThatThrownBy(() -> new Charge(
                        paid.id(),
                        paid.amount(),
                        CREATED_AT,
                        EXPIRES_AT,
                        ChargeStatus.REFUNDED,
                        paid.payments(),
                        List.of(tooMuch)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than");
    }

    @Test
    void cannotBeBuiltPaidWhenItsPaymentWasRefunded() {
        Charge refunded = refunded(paidCharge().refund(newId(), Optional.empty(), Optional.empty(), NOW));

        assertThatThrownBy(() -> new Charge(
                        refunded.id(),
                        refunded.amount(),
                        CREATED_AT,
                        EXPIRES_AT,
                        ChargeStatus.PAID,
                        refunded.payments(),
                        refunded.refunds()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REFUNDED");
    }

    private static Charge paidCharge() {
        return activeCharge().receive(payment(10000, ACCEPTED_AT), ACCEPTED_AT).charge();
    }

    private static RefundId newId() {
        return new RefundId(UUID.randomUUID());
    }

    private static Charge refunded(RefundDecision decision) {
        assertThat(decision).isInstanceOf(RefundDecision.Refunded.class);
        return ((RefundDecision.Refunded) decision).charge();
    }

    private static void assertRefused(RefundDecision decision, RefundRefusal reason) {
        assertThat(decision).isEqualTo(new RefundDecision.Refused(reason));
    }
}
